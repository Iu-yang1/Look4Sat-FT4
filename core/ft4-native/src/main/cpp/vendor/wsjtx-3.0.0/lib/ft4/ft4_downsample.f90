subroutine ft4_downsample(dd,newdata,f0,c)

   use iso_c_binding, only: c_float, c_float_complex, c_loc, c_f_pointer

   include 'ft4_params.f90'
   parameter (NFFT2=NMAX/NDOWN)
   real dd(NMAX)
   complex c(0:NMAX/NDOWN-1)
   complex c1(0:NFFT2-1)
   complex(c_float_complex), target, save :: fft_buffer(NMAX)
   real(c_float), pointer :: x(:)
   real window(0:NFFT2-1), shifted_window(0:NFFT2-1)
   logical first, newdata
   data first/.true./
   save first,window

! 使用固定地址的完整复数工作区承载 R2C FFT，实数视图仅负责写入输入样本。
! 这样可以避免 EQUIVALENCE 与短实际参数在 Flang -O2 下产生未定义别名行为。
   call c_f_pointer(c_loc(fft_buffer(1)),x,(/2*NMAX/))

   df=12000.0/NMAX
   baud=12000.0/NSPS
   if(first) then
      bw_transition = 0.5*baud
      bw_flat = 4*baud
      iwt = bw_transition / df
      iwf = bw_flat / df
      pi=4.0*atan(1.0)
      window(0:iwt-1) = 0.5*(1+cos(pi*(/(i,i=iwt-1,0,-1)/)/iwt))
      window(iwt:iwt+iwf-1)=1.0
      window(iwt+iwf:2*iwt+iwf-1) = 0.5*(1+cos(pi*(/(i,i=0,iwt-1)/)/iwt))
      window(2*iwt+iwf:)=0.0
      iws = baud / df
! 显式环移规避 Flang -O2 对零下标数组自赋值 CSHIFT 的错误 lowering。
      do i=0,NFFT2-1
         shifted_window(i)=window(modulo(i+iws,NFFT2))
      enddo
      window=shifted_window
      first=.false.
   endif

   if(newdata) then
      x(1:NMAX)=dd
      call four2a(fft_buffer,NMAX,1,-1,0)     !r2c FFT to freq domain
   endif
   i0=nint(f0/df)
   c1=0.
   if(i0.ge.0 .and. i0.le.NMAX/2) c1(0)=fft_buffer(i0+1)
   do i=1,NFFT2/2
      if(i0+i.ge.0 .and. i0+i.le.NMAX/2) c1(i)=fft_buffer(i0+i+1)
      if(i0-i.ge.0 .and. i0-i.le.NMAX/2) c1(NFFT2-i)=fft_buffer(i0-i+1)
   enddo
   c1=c1*window/NFFT2
   call four2a(c1,NFFT2,1,1,1)            !c2c FFT back to time domain
   c=c1(0:NMAX/NDOWN-1)

   return
end subroutine ft4_downsample
