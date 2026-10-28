// SPDX-License-Identifier: GPL-3.0-only
// JTTY algorithms: jl1nie/mfsk-core 707ebed2fb2290279e16ac79146c7a14df8b793b.
use super::{guarded, string, write_text};
use mfsk_core::jtty::{assemble::MessageUpdate, pack, rx::{Params, Receiver, Stream}, source::ALPHABET, tx};
use std::{collections::HashMap, ffi::c_char, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicU64, Ordering}}};

struct Session {
    stream: Mutex<Stream>,
    cancelled: AtomicBool,
    rows: Mutex<Vec<MessageUpdate>>,
}
static HANDLES: OnceLock<Mutex<HashMap<u64, Arc<Session>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn handles() -> &'static Mutex<HashMap<u64, Arc<Session>>> { HANDLES.get_or_init(|| Mutex::new(HashMap::new())) }
fn get(id: u64) -> Option<Arc<Session>> { handles().lock().ok()?.get(&id).cloned() }

fn tones(text: &str) -> Option<Vec<u8>> {
    if text.is_empty() || text.len() > 80 || !text.is_ascii() ||
        !text.to_ascii_uppercase().bytes().all(|b| ALPHABET.contains(&b)) { return None; }
    pack::tones(text, pack::ExchangeProfile::Unknown).ok().flatten()
}

#[unsafe(no_mangle)]
pub extern "C" fn l4s_jtty_create() -> u64 {
    guarded(0, || {
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let session = Session { stream: Mutex::new(Stream::new(Arc::new(Receiver::new()), Params::default())),
            cancelled: AtomicBool::new(false), rows: Mutex::new(Vec::new()) };
        handles().lock().unwrap().insert(id, Arc::new(session)); id
    })
}
#[unsafe(no_mangle)]
pub extern "C" fn l4s_jtty_cancel(id: u64) { guarded((), || { if let Some(s)=get(id) { s.cancelled.store(true, Ordering::Release); } }); }
#[unsafe(no_mangle)]
pub extern "C" fn l4s_jtty_close(id: u64) {
    guarded((), || { if let Some(s)=handles().lock().unwrap().remove(&id) { s.cancelled.store(true,Ordering::Release); } });
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_jtty_frames(text: *const c_char) -> i32 {
    guarded(-1, || unsafe { string(text) }.and_then(tones).map(|t| (t.len()/59) as i32).unwrap_or(-1))
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_jtty_generate(text: *const c_char, rate: u32, hz: f32, output: *mut f32, capacity: usize) -> i32 {
    guarded(-1, || {
        if ![12000,24000,48000].contains(&rate) || !hz.is_finite() || !(100.0..=2800.0).contains(&hz) { return -1; }
        let Some(t)=unsafe { string(text) }.and_then(tones) else { return -1; };
        let mut synth=tx::Synth::<f32>::at(&t,hz,1.0,rate as usize*32/1000,rate as f32);
        let count=synth.total_samples();
        if output.is_null() || capacity!=count { return -1; }
        synth.fill(unsafe { std::slice::from_raw_parts_mut(output,capacity) }) as i32
    })
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_jtty_push(id: u64, samples: *const i16, count: usize, center: f32, width: f32) -> i32 {
    guarded(-1, || {
        let Some(s)=get(id) else { return -1; };
        if samples.is_null() || count>12000 || !center.is_finite() || !width.is_finite() || !(100.0..=2800.0).contains(&center) || !(10.0..=2000.0).contains(&width) { return -1; }
        if s.cancelled.load(Ordering::Acquire) { return -3; }
        let mut stream=s.stream.lock().unwrap();
        // Bounded per-window trellis work. No global worker pool; callbacks only
        // collect owned values and never cross the JNI boundary.
        let mut p=if width>100.0 { Params::default() } else { Params::default().embedded() };
        p.f0_hz=center; p.ftol_hz=width; p.nfa_hz=100.0; p.nfb_hz=4000.0;
        p.ch0_only=true;
        // The host batches discovery calls without skipping sample windows.
        // Skipping a trellis window loses asynchronous frame starts.
        p.ladder_budget=Some(if width>100.0 { 8 } else { 4 });
        p.smin_db=4.6;
        stream.set_params(p);
        let mut rows=Vec::new();
        stream.push(unsafe { std::slice::from_raw_parts(samples,count) }, &mut |row| rows.push(row));
        if s.cancelled.load(Ordering::Acquire) { return -3; }
        let count=rows.len().min(128); rows.truncate(count); *s.rows.lock().unwrap()=rows; count as i32
    })
}
#[repr(C)]
pub struct Row {
    id: u64, start: f64, frequency: f32, snr: f32, kind: i32,
    text: [c_char;128], calls: [c_char;128],
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn l4s_jtty_result(id: u64, index: usize, out: *mut Row) -> i32 {
    guarded(-1, || {
        let Some(s)=get(id) else { return -1; };
        let rows=s.rows.lock().unwrap(); let Some(row)=rows.get(index) else { return -1; };
        if out.is_null() { return -1; }
        let mut result=Row { id:row.id,start:row.start_s,frequency:row.f1_hz,snr:row.snr_db,kind:row.kind as i32,text:[0;128],calls:[0;128] };
        write_text(&row.text,&mut result.text);write_text(&row.calls.join(" "),&mut result.calls);
        unsafe { out.write(result); } 0
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn validation_and_length() {
        assert!(tones("CQ K1ABC FN42").is_some()); assert!(tones("TEST ~").is_none());
        assert!(tones("你好").is_none()); assert!(tones(&"A".repeat(81)).is_none());
        assert_eq!(tones(&"A".repeat(80)).unwrap().len(),16*59);
    }
    #[test] fn invalid_and_closed_handles() {
        l4s_jtty_cancel(0);l4s_jtty_close(0);
        let id=l4s_jtty_create();assert_ne!(id,0);l4s_jtty_close(id);l4s_jtty_close(id);
        assert!(get(id).is_none());
    }
    fn pcm(bytes: &[u8]) -> Vec<i16> {
        let mut at=12;
        while at+8<=bytes.len() {
            let n=u32::from_le_bytes(bytes[at+4..at+8].try_into().unwrap()) as usize;
            if &bytes[at..at+4]==b"data" { return bytes[at+8..at+8+n].chunks_exact(2).map(|b| i16::from_le_bytes([b[0],b[1]])).collect(); }
            at+=8+n+n%2;
        }
        panic!("Missing independent WAV data")
    }
    #[test] fn wsjtx_payload_and_tone_oracles() {
        let mut checked=0;
        for line in include_str!("../tests/fixtures/jtty/pack_cases.tsv").lines().filter(|l| !l.starts_with('#')) {
            let f:Vec<_>=line.split('\t').collect();if f[0]!="0" { continue; }
            let expected:i32=f[2].parse().unwrap();let packed=pack::pack(f[1],pack::ExchangeProfile::Unknown);
            if expected<0 { assert!(packed.is_err());continue; }
            let atoms=packed.unwrap();assert_eq!(atoms.len(),expected as usize);
            if !atoms.is_empty() {
                let words=tx::payloads(&atoms).unwrap().iter().map(|p|format!("{:09X}",p.iter().fold(0u64,|n,b| (n<<1)|*b as u64))).collect::<Vec<_>>().join(" ");
                assert_eq!(words,f[3],"{}",f[1]);
            }
            checked+=1;
        }
        assert!(checked>1000,"only {checked} reference payload cases");
        for line in include_str!("../tests/fixtures/jtty/MANIFEST.tsv").lines().filter(|l| !l.starts_with('#')) {
            let f:Vec<_>=line.split('\t').collect();if f[1]!="unknown" {continue;}
            let expected=f[7].split_whitespace().map(|v|v.parse::<u8>().unwrap()).collect::<Vec<_>>();
            assert_eq!(tones(f[2]).unwrap(),expected,"{}",f[0]);
        }
    }
    #[test] fn wsjtx_waveform_oracle_and_all_output_rates() {
        for (text,bytes) in [
            ("CQ K1ABC CQ",include_bytes!("../tests/fixtures/jtty/clean_cq_1frame.wav").as_slice()),
            ("WB9XYZ 599 123",include_bytes!("../tests/fixtures/jtty/clean_call_exch_2frames.wav").as_slice())
        ] {
            let reference=pcm(bytes);let t=tones(text).unwrap();
            let ours=tx::synth_f32(&t,1500.0,1.0);
            let theirs=&reference[3600..3600+ours.len()];
            let peak=theirs.iter().map(|s|s.unsigned_abs()).max().unwrap() as f32;
            let our_peak=ours.iter().map(|s|s.abs()).fold(0f32,f32::max);
            let error=ours.iter().zip(theirs).map(|(a,b)|(a/our_peak-*b as f32/peak).abs()).fold(0f32,f32::max);
            assert!(error<0.002,"{text} maximum sample error {error}");
            for rate in [12000u32,24000,48000] {
                let count=t.len()*rate as usize*32/1000;let mut output=vec![0f32;count];
                let input=std::ffi::CString::new(text).unwrap();
                assert_eq!(unsafe { l4s_jtty_generate(input.as_ptr(),rate,1500.0,output.as_mut_ptr(),output.len()) },count as i32);
                assert!(output.iter().all(|s|s.is_finite() && s.abs()<=1.001));
                assert!(output[0].abs()<0.001 && output.last().unwrap().abs()<0.01);
            }
        }
    }
    #[test] fn independent_streaming_receive_and_no_cross_gap_assembly() {
        let id=l4s_jtty_create();let audio=pcm(include_bytes!("../tests/fixtures/jtty/call_exch_2frames.wav"));
        let mut found=false;
        for block in audio.chunks(512) {
            let count=unsafe { l4s_jtty_push(id,block.as_ptr(),block.len(),1500.0,50.0) };assert!(count>=0);
            let session=get(id).unwrap();
            for row in session.rows.lock().unwrap().iter() { if row.text=="WB9XYZ 599 123" && row.complete { found=true; } }
        }
        assert!(found,"Missing independent two-frame message");l4s_jtty_close(id);
        let id=l4s_jtty_create();let partial=&audio[audio.len()/2..];
        for block in partial.chunks(512) {
            assert!(unsafe { l4s_jtty_push(id,block.as_ptr(),block.len(),1500.0,50.0) }>=0);
            assert!(get(id).unwrap().rows.lock().unwrap().iter().all(|r|r.text!="WB9XYZ 599 123"));
        }
        l4s_jtty_cancel(id);assert_eq!(unsafe { l4s_jtty_push(id,audio.as_ptr(),512,1500.0,50.0) },-3);l4s_jtty_close(id);
    }
    fn decode(bytes: &[u8], centers: &[f32]) -> Vec<String> {
        let audio=pcm(bytes);let mut result=Vec::new();
        for &center in centers {
            let id=l4s_jtty_create();
            for block in audio.chunks(512) {
                assert!(unsafe { l4s_jtty_push(id,block.as_ptr(),block.len(),center,50.0) }>=0);
                for row in get(id).unwrap().rows.lock().unwrap().iter() { if row.complete { result.push(row.text.clone()); } }
            }
            l4s_jtty_close(id);
        }
        result.sort();result.dedup();result
    }
    #[test] fn independent_overlapping_carriers_fading_and_drift() {
        let messages=decode(include_bytes!("../tests/fixtures/jtty/mix_four_stations.wav"),&[1300.0,1500.0,1650.0,1750.0]);
        for expected in ["CQ K1ABC CQ","CQ W9XYZ CQ","CQ JA6DEF CQ","CQ VK3NV CQ"] { assert!(messages.iter().any(|s|s==expected),"Missing {expected}: {messages:?}"); }
        let fading=decode(include_bytes!("../tests/fixtures/jtty/mix_fading_pair.wav"),&[1500.0]);
        assert!(fading.iter().any(|s|s=="CQ K1ABC CQ"));
        let drift=decode(include_bytes!("../tests/fixtures/jtty/mix_back_to_back.wav"),&[1500.0]);
        assert!(drift.iter().any(|s|s=="CQ K1ABC CQ"));assert!(drift.iter().any(|s|s=="K1ABC TU"));
    }
    #[test] fn discovery_is_not_gated_by_an_fft_candidate() {
        let id=l4s_jtty_create();let audio=pcm(include_bytes!("../tests/fixtures/jtty/cq_1frame.wav"));let mut found=false;
        for block in audio.chunks(512) {
            assert!(unsafe { l4s_jtty_push(id,block.as_ptr(),block.len(),2050.0,1950.0) }>=0);
            found |= get(id).unwrap().rows.lock().unwrap().iter().any(|r|r.text=="CQ K1ABC CQ" && r.complete);
        }
        assert!(found,"Wide discovery missed the independent carrier");l4s_jtty_close(id);
    }
    #[test] fn fixed_seed_noise_has_no_messages() {
        let id=l4s_jtty_create();let mut seed=0x12345678u32;
        for _ in 0..240 {
            let block:Vec<i16>=(0..512).map(|_| {seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);((seed>>20) as i16)-2048}).collect();
            let count=unsafe { l4s_jtty_push(id,block.as_ptr(),block.len(),1500.0,50.0) };assert_eq!(count,0);
        }
        l4s_jtty_close(id);
    }
}
