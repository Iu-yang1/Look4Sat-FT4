// SPDX-License-Identifier: GPL-3.0-only
// FT4-only Android adapter for mfsk-core, Copyright Look4Sat contributors.
// Algorithm source: jl1nie/mfsk-core 707ebed2fb2290279e16ac79146c7a14df8b793b,
// a GPL-3.0 Rust implementation of WSJT-X by K1JT and collaborators.
use mfsk_core::decoder::{DecodeParams, Decoder, Depth, Ft4Strategy, SlotInput};
use mfsk_core::engine::tx::message_to_tones;
use mfsk_core::ft4::Ft4;
use mfsk_core::msg::wsjt77::{pack77_message, Pack77Options};
use std::collections::HashMap;
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

pub const UPSTREAM: &str = "707ebed2fb2290279e16ac79146c7a14df8b793b";
pub const RATE: usize = 12_000;
pub const SLOT: usize = 90_000;
pub const START_OFFSET_MS: usize = 500;

#[repr(C)]
#[derive(Clone)]
pub struct Row {
    pub snr: i32,
    pub dt: f32,
    pub frequency: f32,
    pub hash: u64,
    pub text: [c_char; 128],
    pub source: [c_char; 32],
    pub target: [c_char; 32],
    pub detail: [c_char; 64],
}

struct Session {
    decoder: Mutex<Decoder<Ft4>>,
    cancelled: AtomicBool,
    busy: AtomicBool,
    budget_exhausted: AtomicBool,
    results: Mutex<Vec<Row>>,
}

static HANDLES: OnceLock<Mutex<HashMap<u64, Arc<Session>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn handles() -> &'static Mutex<HashMap<u64, Arc<Session>>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn get(id: u64) -> Option<Arc<Session>> {
    handles().lock().ok()?.get(&id).cloned()
}
fn guarded<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}
unsafe fn string<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() { return None; }
    unsafe { CStr::from_ptr(ptr) }.to_str().ok()
}
fn write_text(value: &str, output: &mut [c_char]) {
    output.fill(0);
    let count = value.len().min(output.len().saturating_sub(1));
    for (out, byte) in output.iter_mut().zip(value.bytes()).take(count) { *out = byte as c_char; }
}
fn canonical(text: &str) -> Option<(String, [u8; 77])> {
    if text.len() > 128 || !text.is_ascii() { return None; }
    let normalized = text.split_whitespace().collect::<Vec<_>>().join(" ").to_uppercase();
    let packed = pack77_message(&normalized, &Pack77Options::new()).ok()?;
    // Use the packer's facts to resolve non-standard calls without teaching an RX session.
    let mut decoder = Decoder::<Ft4>::with_defaults();
    for call in &packed.hashed_calls { decoder.learn_callsign(call); }
    Some((decoder.unpack77(&packed.bits)?, packed.bits))
}
pub fn waveform(text: &str, rate: u32, frequency: f32) -> Option<Vec<f32>> {
    if ![12_000, 24_000, 48_000].contains(&rate) || !frequency.is_finite() || !(0.0..=3000.0).contains(&frequency) { return None; }
    let (_, bits) = canonical(text)?;
    let tones = message_to_tones::<Ft4>(&bits);
    Some(ft4_waveform(&tones,rate,frequency))
}

// WSJT-X gen_ft4wave.f90: FT4 has two full-symbol ramps, unlike FT8's NSPS/8
// envelope. The pinned generic mfsk-core synth omits these FT4-only symbols.
// Keep coding and decoding upstream, and correct only this transmit boundary.
fn ft4_waveform(tones: &[u8], rate: u32, frequency: f32) -> Vec<f32> {
    let nsps = (rate as usize * 48) / 1000;
    let twopi = 8.0 * libm::atanf(1.0);
    let c = (twopi/2.0) * libm::sqrtf(2.0/libm::logf(2.0));
    let pulse: Vec<f32> = (1..=3*nsps).map(|i| {
        let t=(i as f32-1.5*nsps as f32)/nsps as f32;
        0.5*(libm::erff(c*(t+0.5))-libm::erff(c*(t-0.5)))
    }).collect();
    let mut increments=vec![0.0f32; (tones.len()+2)*nsps];
    let peak=twopi/nsps as f32;
    for (j,tone) in tones.iter().enumerate() {
        for (i,p) in pulse.iter().enumerate() { increments[j*nsps+i] += peak*p*(*tone as f32); }
    }
    let shift=twopi*frequency/rate as f32;
    let mut phase=0.0f32;
    let mut wave=Vec::with_capacity(increments.len());
    for d in increments {
        wave.push(libm::sinf(phase)); phase=(phase+(d+shift))%twopi;
    }
    for i in 0..nsps {
        let ramp=libm::cosf(twopi*i as f32/(2.0*nsps as f32));
        wave[i] *= (1.0-ramp)/2.0;
        let end=(tones.len()+1)*nsps+i;
        wave[end] *= (1.0+ramp)/2.0;
    }
    wave
}

#[unsafe(no_mangle)]
pub extern "C" fn l4s_ft4_create() -> u64 {
    guarded(0, || {
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let session = Session { decoder: Mutex::new(Decoder::<Ft4>::with_defaults()),
            cancelled: AtomicBool::new(false), busy: AtomicBool::new(false), budget_exhausted: AtomicBool::new(false), results: Mutex::new(Vec::new()) };
        handles().lock().unwrap().insert(id, Arc::new(session)); id
    })
}
#[unsafe(no_mangle)]
pub extern "C" fn l4s_ft4_cancel(id: u64) {
    guarded((), || { if let Some(s) = get(id) { s.cancelled.store(true, Ordering::Release); } });
}
#[unsafe(no_mangle)]
pub extern "C" fn l4s_ft4_close(id: u64) {
    guarded((), || {
        if let Some(session) = handles().lock().unwrap().remove(&id) {
            session.cancelled.store(true, Ordering::Release);
            // A decode keeps its Arc until it has stopped. No pointer can outlive its allocation.
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn l4s_ft4_reset(id: u64) -> i32 {
    guarded(-1, || {
        let Some(s)=get(id) else { return -1; };
        if s.busy.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err() { return -2; }
        let _busy=Busy(&s.busy);
        s.decoder.lock().unwrap().clear();s.results.lock().unwrap().clear();
        s.cancelled.store(false,Ordering::Release);s.budget_exhausted.store(false,Ordering::Release);0
    })
}
#[unsafe(no_mangle)]
pub extern "C" fn l4s_ft4_budget_exhausted(id: u64) -> bool {
    guarded(true, || get(id).map(|s| s.budget_exhausted.load(Ordering::Acquire)).unwrap_or(true))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_tones(text: *const c_char, output: *mut u8, capacity: usize) -> i32 {
    guarded(-1, || {
        let Some((_, bits)) = unsafe { string(text) }.and_then(canonical) else { return -1; };
        let tones = message_to_tones::<Ft4>(&bits);
        if output.is_null() || capacity < tones.len() { return -1; }
        unsafe { std::ptr::copy_nonoverlapping(tones.as_ptr(), output, tones.len()); } tones.len() as i32
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_bits(text: *const c_char, output: *mut u8, capacity: usize) -> i32 {
    guarded(-1, || {
        let Some((_, bits)) = unsafe { string(text) }.and_then(canonical) else { return -1; };
        if output.is_null() || capacity<77 { return -1; }
        unsafe { std::ptr::copy_nonoverlapping(bits.as_ptr(),output,77); } 77
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_validate(text: *const c_char, output: *mut c_char, capacity: usize) -> i32 {
    guarded(-1, || {
        let Some((normalized, _)) = unsafe { string(text) }.and_then(canonical) else { return -1; };
        if output.is_null() || normalized.len() >= capacity { return -1; }
        write_text(&normalized, unsafe { std::slice::from_raw_parts_mut(output, capacity) }); 0
    })
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_generate(text: *const c_char, rate: u32, frequency: f32, output: *mut f32, capacity: usize) -> i32 {
    guarded(-1, || {
        let Some(samples) = unsafe { string(text) }.and_then(|t| waveform(t, rate, frequency)) else { return -1; };
        if output.is_null() || capacity != samples.len() { return -1; }
        unsafe { std::ptr::copy_nonoverlapping(samples.as_ptr(), output, samples.len()); }
        samples.len() as i32
    })
}

struct Busy<'a>(&'a AtomicBool);
impl Drop for Busy<'_> { fn drop(&mut self) { self.0.store(false, Ordering::Release); } }

#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_decode(id: u64, input: *const f32, count: usize, period: i64,
    depth: u32, rounds: usize, wideband: bool, qso_hz: f32, my_call: *const c_char,
    his_call: *const c_char, budget_ms: u64) -> i32 {
    guarded(-1, || {
        let Some(session) = get(id) else { return -1; };
        if input.is_null() || count != SLOT || !(1..=3).contains(&depth) || !(1..=3).contains(&rounds) { return -1; }
        if session.busy.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_err() { return -2; }
        let _busy = Busy(&session.busy);
        if session.cancelled.load(Ordering::Acquire) { return -3; }
        let samples = unsafe { std::slice::from_raw_parts(input, count) };
        if !samples.iter().all(|s| s.is_finite()) { return -1; }
        let mut decoder = session.decoder.lock().unwrap();
        let own = unsafe { string(my_call) }.unwrap_or("");
        let other = unsafe { string(his_call) }.unwrap_or("");
        let band = if wideband { (0.0, 3000.0) } else { ((qso_hz-100.0).max(0.0), (qso_hz+100.0).min(3000.0)) };
        *decoder.params_mut() = DecodeParams::for_band(band).rx_freq(qso_hz).tx_freq(qso_hz)
            .depth(match depth { 1 => Depth::Fast, 2 => Depth::Normal, _ => Depth::Deep }).station(own, "");
        decoder.extras_mut().tuning.strategy = Some(Ft4Strategy::SicRounds(rounds));
        for call in [own, other] { if !call.is_empty() { decoder.learn_callsign(call); } }
        let deadline = Instant::now() + Duration::from_millis(budget_ms.clamp(1, 10_000));
        let keep_going = || !session.cancelled.load(Ordering::Acquire) && Instant::now() < deadline;
        let result = decoder.decode(&SlotInput::f32(samples).period(period).budget(&keep_going));
        session.budget_exhausted.store(result.budget.exhausted,Ordering::Release);
        let mut rows = Vec::new();
        for row in result.rows {
            let mut identity = 0xcbf29ce484222325u64;
            for bit in row.detail.info.iter().take(77) { identity = (identity ^ *bit as u64).wrapping_mul(0x100000001b3); }
            let mut out = Row { snr: row.decoded.snr_db.round() as i32, dt: row.decoded.dt_sec,
                frequency: row.decoded.freq_hz, hash: identity, text: [0; 128],source:[0;32],target:[0;32],detail:[0;64] };
            write_text(&row.decoded.text, &mut out.text);
            let mut table = mfsk_core::msg::CallsignHashTable::new();
            for token in row.decoded.text.split_whitespace().chain([own,other]) {
                if mfsk_core::msg::wsjt77::is_valid_callsign(token.trim_matches(['<','>'])) { table.insert(token.trim_matches(['<','>'])); }
            }
            if let Some(fields) = mfsk_core::msg::wsjt77::unpack77_fields(row.native.message77(),&table) {
                let calls:Vec<_>=fields.callsigns().collect();
                if calls.len()>=2 {
                    write_text(calls[0],&mut out.target);write_text(calls[1],&mut out.source);
                    let prefix=format!("{} {}",calls[0],calls[1]);
                    write_text(row.decoded.text.strip_prefix(&prefix).unwrap_or("").trim(),&mut out.detail);
                } else if row.decoded.text.starts_with("CQ ") && calls.len()==1 { write_text("CQ",&mut out.target);write_text(calls[0],&mut out.source); }
            }
            rows.push(out);
        }
        let count = rows.len(); *session.results.lock().unwrap() = rows;
        if session.cancelled.load(Ordering::Acquire) { -3 } else { count as i32 }
    })
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_ft4_result(id: u64, index: usize, output: *mut Row) -> i32 {
    guarded(-1, || {
        if output.is_null() { return -1; }
        let Some(session) = get(id) else { return -1; };
        let rows = session.results.lock().unwrap();
        let Some(row) = rows.get(index) else { return -1; };
        unsafe { output.write(row.clone()); } 0
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn golden_pcm() -> Vec<i16> {
        let bytes=include_bytes!("../tests/fixtures/wsjtx-ft4.wav");let mut at=12;
        while at+8<=bytes.len() {
            let length=u32::from_le_bytes(bytes[at+4..at+8].try_into().unwrap()) as usize;
            if &bytes[at..at+4]==b"data" {
                let mut pcm:Vec<i16>=bytes[at+8..at+8+length].chunks_exact(2).map(|b|i16::from_le_bytes([b[0],b[1]])).collect();
                assert_eq!(pcm.len(),72576);pcm.resize(SLOT,0);return pcm;
            }
            at+=8+length+length%2;
        }
        panic!("Missing golden PCM");
    }
    #[test] fn message_and_waveform_contract() {
        for text in ["CQ K1ABC FN42", "K1ABC W9XYZ -12", "K1ABC W9XYZ RR73", "TNX 73", "CQ PJ4/K1ABC"] {
            assert!(canonical(text).is_some(), "{text}");
        }
        assert!(canonical("THIS MESSAGE IS MUCH TOO LONG TO PACK AS FREE TEXT").is_none());
        for rate in [12_000,24_000,48_000] {
            let a = waveform("CQ K1ABC FN42",rate,1500.0).unwrap();
            assert_eq!(a.len(), rate as usize * 504 / 100);
            assert!(a.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
            assert!(a[0].abs() < 0.01 && a.last().unwrap().abs() < 0.01);
        }
    }
    #[test] fn invalid_and_double_closed_handles_are_safe() {
        let id = l4s_ft4_create(); assert_ne!(id,0);
        l4s_ft4_cancel(id); l4s_ft4_close(id); l4s_ft4_close(id); l4s_ft4_cancel(id);
        assert!(get(id).is_none());
    }
    #[test] fn independent_wsjtx_golden_rows_and_measurements() {
        let pcm=golden_pcm();
        let mut decoder = Decoder::<Ft4>::new(DecodeParams::for_band((300.0,2700.0)).depth(Depth::Deep));
        decoder.extras_mut().tuning.strategy = Some(Ft4Strategy::SicRounds(3));
        let result = decoder.decode(&SlotInput::i16(&pcm));
        for line in include_str!("../tests/fixtures/wsjtx-ft4-reference.tsv").lines() {
            let fields: Vec<_> = line.split('\t').collect();
            let row = result.rows.iter().find(|r| r.decoded.text==fields[0]).unwrap_or_else(|| panic!("Missing {}. Found {:?}",fields[0],result.rows.iter().map(|r| &r.decoded.text).collect::<Vec<_>>()));
            assert!((row.decoded.freq_hz-fields[1].parse::<f32>().unwrap()).abs()<=12.0);
            assert!((row.decoded.dt_sec-fields[2].parse::<f32>().unwrap()).abs()<=0.3);
            assert!((row.decoded.snr_db-fields[3].parse::<f32>().unwrap()).abs()<=4.0);
        }
        assert_eq!(result.rows.len(),14,"Unexpected decode outside the independent reference");
    }
    #[test] fn cancellation_and_close_cannot_free_a_live_decoder() {
        let id=l4s_ft4_create();let pcm:Vec<f32>=golden_pcm().iter().map(|s|*s as f32/32768.0).collect();
        let started=Instant::now();
        let worker=std::thread::spawn(move || unsafe { l4s_ft4_decode(id,pcm.as_ptr(),SLOT,0,3,3,true,1500.0,std::ptr::null(),std::ptr::null(),10000) });
        std::thread::sleep(Duration::from_millis(2));l4s_ft4_cancel(id);l4s_ft4_close(id);
        assert!(worker.join().unwrap()<0);assert!(started.elapsed()<Duration::from_secs(1));
        assert_eq!(l4s_ft4_reset(id),-1);
    }
    #[test] fn budget_and_reset_are_observable() {
        let id=l4s_ft4_create();let pcm:Vec<f32>=golden_pcm().iter().map(|s|*s as f32/32768.0).collect();
        assert!(unsafe { l4s_ft4_decode(id,pcm.as_ptr(),SLOT,0,3,3,true,1500.0,std::ptr::null(),std::ptr::null(),1) }>=0);
        assert!(l4s_ft4_budget_exhausted(id));l4s_ft4_cancel(id);
        assert_eq!(l4s_ft4_reset(id),0);assert!(!l4s_ft4_budget_exhausted(id));
        l4s_ft4_close(id);
    }
}
