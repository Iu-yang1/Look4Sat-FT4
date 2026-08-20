subroutine getcandidates4(dd,fa,fb,syncmin,nfqso,maxcand,savg,candidate,   &
     ncand,sbase)

  use iso_c_binding, only: c_float, c_float_complex, c_loc, c_f_pointer

  include 'ft4_params.f90'
  real s(NH1,NHSYM)
  real savg(NH1),savsm(NH1)
  real sbase(NH1)
  real(c_float), pointer :: x(:)
  real window(NFFT1)
  complex(c_float_complex), target, save :: fft_buffer(NFFT1)
  real candidate(2,maxcand),candidatet(2,maxcand)
  real dd(NMAX)
  logical first
  data first/.true./
  save first,window

! 使用容量完整且地址稳定的复数工作区，并以实数视图写入 R2C 输入。
! 这避免 EQUIVALENCE 和短实际参数在 Flang -O2 下触发未定义别名优化。
  call c_f_pointer(c_loc(fft_buffer(1)),x,(/2*NFFT1/))

  if(first) then
    first=.false.
    pi=4.0*atan(1.)
    window=0.
    call nuttal_window(window,NFFT1)
  endif

! Compute symbol spectra, stepping by NSTEP steps.
  savg=0.
  df=12000.0/NFFT1
  fac=1.0/300.0
  do j=1,NHSYM
     ia=(j-1)*NSTEP + 1
     ib=ia+NFFT1-1
     if(ib.gt.NMAX) exit
     x(1:NFFT1)=fac*dd(ia:ib)*window
     call four2a(fft_buffer,NFFT1,1,-1,0)      !r2c FFT
     s(1:NH1,j)=abs(fft_buffer(2:NH1+1))**2
     savg=savg + s(1:NH1,j)                   !Average spectrum
  enddo
  savg=savg/NHSYM
  savsm=0.
  do i=8,NH1-7
    savsm(i)=sum(savg(i-7:i+7))/15.
  enddo

  nfa=fa/df
  if(nfa.lt.nint(200.0/df)) nfa=nint(200.0/df)
  nfb=fb/df
  if(nfb.gt.nint(4910.0/df)) nfb=nint(4910.0/df)
  call ft4_baseline(savg,nfa,nfb,sbase)
  if(any(sbase(nfa:nfb).le.0)) return
  savsm(nfa:nfb)=savsm(nfa:nfb)/sbase(nfa:nfb)
  f_offset = -1.5*12000.0/NSPS
  ncand=0
  candidatet=0
  do i=nfa+1,nfb-1
     if(savsm(i).ge.savsm(i-1) .and. savsm(i).ge.savsm(i+1) .and.      &
          savsm(i).ge.syncmin) then
        den=savsm(i-1)-2*savsm(i)+savsm(i+1)
        del=0.
        if(den.ne.0.0)  del=0.5*(savsm(i-1)-savsm(i+1))/den
        fpeak=(i+del)*df+f_offset
        if(fpeak.lt.200.0 .or. fpeak.gt.4910.0) cycle
        speak=savsm(i) - 0.25*(savsm(i-1)-savsm(i+1))*del
        ncand=ncand+1
        candidatet(1,ncand)=fpeak
        candidatet(2,ncand)=speak
        if(ncand.eq.maxcand) exit
     endif
  enddo
  candidate=0
  nq=count(abs(candidatet(1,1:ncand)-nfqso).le.20.0)
  n1=1
  n2=nq+1
  do i=1,ncand
     if(abs(candidatet(1,i)-nfqso).le.20.0) then
        candidate(1:2,n1)=candidatet(1:2,i)
        n1=n1+1
     else
        candidate(1:2,n2)=candidatet(1:2,i)
        n2=n2+1
     endif
  enddo
return
end subroutine getcandidates4
