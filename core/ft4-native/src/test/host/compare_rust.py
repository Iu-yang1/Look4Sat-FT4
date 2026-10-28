"""Offline independent WSJT-X comparison. No radio or Android audio is opened."""
import argparse
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import subprocess
import wave
import numpy as np


class Row(c.Structure):
    _fields_ = [("snr", c.c_int32), ("dt", c.c_float), ("frequency", c.c_float),
                ("hash", c.c_uint64), ("text", c.c_char * 128), ("source", c.c_char * 32),
                ("target", c.c_char * 32), ("detail", c.c_char * 64)]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--rust", required=True)
    p.add_argument("--fortran", required=True)
    p.add_argument("--jt9", required=True)
    p.add_argument("--work", required=True)
    p.add_argument("--dll-path", action="append", default=[])
    p.add_argument("--sweep", action="store_true")
    p.add_argument("--trials", type=int, default=20)
    a = p.parse_args()
    dll_handles = [os.add_dll_directory(str(Path(d).resolve())) for d in a.dll_path] if os.name == "nt" else []
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join([*a.dll_path, env.get("PATH", "")])
    rust, ref = c.CDLL(str(Path(a.rust).resolve())), c.CDLL(str(Path(a.fortran).resolve()))
    rust.l4s_ft4_generate.argtypes = [c.c_char_p, c.c_uint32, c.c_float, c.c_void_p, c.c_size_t]
    rust.l4s_ft4_tones.argtypes = [c.c_char_p, c.c_void_p, c.c_size_t]
    rust.l4s_ft4_bits.argtypes = [c.c_char_p, c.c_void_p, c.c_size_t]
    rust.l4s_ft4_create.restype = c.c_uint64
    rust.l4s_ft4_close.argtypes = [c.c_uint64]
    rust.l4s_ft4_decode.argtypes = [c.c_uint64,c.c_void_p,c.c_size_t,c.c_int64,c.c_uint32,c.c_size_t,c.c_bool,c.c_float,c.c_char_p,c.c_char_p,c.c_uint64]
    rust.l4s_ft4_result.argtypes = [c.c_uint64,c.c_size_t,c.POINTER(Row)]
    ref.reference_ft4_wave.argtypes = [c.c_char_p,c.c_int,c.c_float,c.c_void_p,c.c_int]
    ref.reference_ft4_tones.argtypes = [c.c_char_p,c.c_void_p,c.c_int]
    ref.reference_ft4_wave_tones.argtypes = [c.c_void_p,c.c_int,c.c_float,c.c_void_p,c.c_int]
    work = Path(a.work).resolve(); work.mkdir(parents=True, exist_ok=True)
    report = {"mfsk_core_commit":"707ebed2fb2290279e16ac79146c7a14df8b793b", "numpy_version":np.__version__,
              "trials_per_level":a.trials,"seed_base":0x4F5434,
              "legacy_fortran_sha256":hashlib.sha256(Path(a.fortran).read_bytes()).hexdigest(),
              "rust_sha256": hashlib.sha256(Path(a.rust).read_bytes()).hexdigest(),
              "oracle_sha256": hashlib.sha256(Path(a.jt9).read_bytes()).hexdigest(), "waveforms": []}
    messages = ["CQ K1ABC FN42", "K1ABC W9XYZ -12", "K1ABC W9XYZ RR73", "TNX 73", "CQ PJ4/K1ABC"]
    for message in messages:
        text = message.encode("ascii")
        actual_tones = np.zeros(103, dtype=np.uint8)
        expected_tones = np.zeros(105, dtype=np.int32)
        assert rust.l4s_ft4_tones(text,actual_tones.ctypes.data,103) == 103
        assert ref.reference_ft4_tones(text,expected_tones.ctypes.data,105) == 105
        known_rr73 = message == "K1ABC W9XYZ RR73"
        if known_rr73:
            # mfsk-core pins the later WSJT-X grammar: RR73 acknowledgment 32403,
            # whereas the legacy comparator sends the grid RR73 (32373).
            bits=np.zeros(77,dtype=np.uint8)
            assert rust.l4s_ft4_bits(text,bits.ctypes.data,77)==77
            assert int("".join(map(str,bits[59:74])),2)==32403
            assert not np.array_equal(actual_tones,expected_tones[1:-1])
        else:
            assert np.array_equal(actual_tones,expected_tones[1:-1]), message
        for rate in [12000,24000,48000]:
            count = rate * 504 // 100
            actual, expected = np.zeros(count,dtype=np.float32), np.zeros(count,dtype=np.float32)
            assert rust.l4s_ft4_generate(text,rate,1500,actual.ctypes.data,count) == count
            if known_rr73:
                tones=actual_tones.astype(np.int32)
                assert ref.reference_ft4_wave_tones(tones.ctypes.data,rate,1500,expected.ctypes.data,count)==count
            else:
                assert ref.reference_ft4_wave(text,rate,1500,expected.ctypes.data,count) == count
            difference = actual.astype(np.float64)-expected.astype(np.float64)
            rms, peak = float(np.sqrt(np.mean(difference*difference))), float(np.max(np.abs(difference)))
            report["waveforms"].append({"message":message,"rate":rate,"samples":count,"rms_error":rms,"peak_error":peak,"versioned_rr73_encoding":known_rr73})
            assert rms < 0.015 and peak < 0.05, report["waveforms"][-1]

    # Independent decoder, not the Rust receiver, must accept every generated TX form.
    report["transmit_interop"] = []
    for index,message in enumerate(messages):
        folder=work/"tx_interop"/str(index);folder.mkdir(parents=True,exist_ok=True)
        samples=np.random.default_rng(0x465434+index).normal(0,0.00001,90000).astype(np.float32)
        frame=np.zeros(60480,dtype=np.float32)
        assert rust.l4s_ft4_generate(message.encode(),12000,1500,frame.ctypes.data,frame.size)==frame.size
        samples[6000:6000+frame.size]+=frame*0.5
        wav=folder/"000000_000000.wav"
        with wave.open(str(wav),"wb") as f:
            f.setparams((1,2,12000,90000,"NONE","not compressed"));f.writeframes(np.round(samples*32767).astype("<i2").tobytes())
        result=subprocess.run([str(Path(a.jt9).resolve()),"-5","-p","15","-L","100","-H","3000","-d","3",str(wav)],
                              cwd=folder,env=env,capture_output=True,timeout=30)
        text=(result.stdout+result.stderr).decode("utf-8","replace")
        assert result.returncode==0 and message in text,(message,result.returncode,text)
        report["transmit_interop"].append({"message":message,"decoded_by_official_wsjtx":True})

    def decode(samples, index):
        handle=rust.l4s_ft4_create()
        try:
            n=rust.l4s_ft4_decode(handle,samples.ctypes.data,90000,index,3,3,True,1500,b"ZZ9ZZZ",b"",10000)
            assert n>=0, n
            rows=[]
            for i in range(n):
                r=Row(); assert rust.l4s_ft4_result(handle,i,c.byref(r))==0;rows.append(r.text.decode("ascii"))
            return rows
        finally: rust.l4s_ft4_close(handle)

    if a.sweep:
        message = "CQ K1ABC FN42"
        clean = np.zeros(60480,dtype=np.float32)
        assert ref.reference_ft4_wave(message.encode(),12000,1500,clean.ctypes.data,clean.size)==clean.size
        signal_power=float(np.mean(clean.astype(np.float64)**2))
        scores=[]; false_counts={"rust":0,"wsjtx":0}; corpus=hashlib.sha256()
        jt9=str(Path(a.jt9).resolve())
        for snr in list(range(-23,-11))+[None]:
            hits={"rust":0,"wsjtx":0}
            for trial in range(a.trials):
                rng=np.random.default_rng(0x4F5434+trial)
                samples=rng.normal(0,0.03,90000).astype(np.float32)
                if snr is not None:
                    amplitude=0.03*np.sqrt(10**(snr/10)*2500/(6000*signal_power))
                    samples[6000:6000+clean.size] += clean*amplitude
                integer=np.round(np.clip(samples,-1,1)*32767).astype("<i2")
                corpus.update(integer.tobytes())
                samples=integer.astype(np.float32)/32768
                folder=work/("noise" if snr is None else f"snr{snr}")/f"{trial:03d}"
                folder.mkdir(parents=True,exist_ok=True)
                wav=folder/"000000_000000.wav"
                with wave.open(str(wav),"wb") as f:
                    f.setparams((1,2,12000,90000,"NONE","not compressed"));f.writeframes(integer.tobytes())
                result=subprocess.run([jt9,"-5","-p","15","-L","100","-H","3000","-d","3","-c","ZZ9ZZZ","-X","0",str(wav)],
                                      cwd=folder,env=env,capture_output=True,timeout=30)
                assert result.returncode==0,(result.returncode,result.stderr)
                text=(result.stdout+result.stderr).decode("utf-8","replace")
                rows=decode(samples,trial)
                hits["rust"]+=message in rows;hits["wsjtx"]+=message in text
                if snr is None:
                    false_counts["rust"]+=len(rows)
                    false_counts["wsjtx"]+=sum(1 for line in text.splitlines() if " + " in line or " ~ " in line)
            scores.append({"snr":snr,**hits});print(scores[-1],flush=True)
        def threshold(engine):
            previous=None
            for row in scores[:-1]:
                fraction=row[engine]/a.trials
                if fraction>=0.5:
                    assert previous is not None,"Extend the lower SNR range"
                    return previous[0]+(row["snr"]-previous[0])*(0.5-previous[1])/(fraction-previous[1])
                previous=(row["snr"],fraction)
            raise AssertionError("Extend the upper SNR range")
        degradation=threshold("rust")-threshold("wsjtx")
        report.update(scores=scores,false_decodes=false_counts,threshold_degradation_db=degradation,corpus_sha256=corpus.hexdigest())
        (work/"result.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
        assert degradation<=0.5,report
        assert false_counts["rust"]<=false_counts["wsjtx"],report
    (work/"result.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report,indent=2))


if __name__ == "__main__": main()
