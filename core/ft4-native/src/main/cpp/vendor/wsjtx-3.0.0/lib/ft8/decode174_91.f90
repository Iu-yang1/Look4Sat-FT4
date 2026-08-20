subroutine decode174_91(llr,Keff,maxosd,norder,apmask,message91,cw,ntype,nharderror,dmin)
!
! A hybrid bp/osd decoder for the (174,91) code.
!
! maxosd<0: do bp only
! maxosd=0: do bp and then call osd once with channel llrs
! maxosd>1: do bp and then call osd maxosd times with saved bp outputs
! norder  : osd decoding depth
!
   use iso_c_binding, only: c_int, c_long_long
   integer, parameter:: N=174, K=91, M=N-K
   integer*1 cw(N),apmask(N)
   integer*1 nxor(N),hdec(N)
   integer*1 message91(91),m96(96)
   integer nrw(M),ncw
   integer Nm(7,M)
   integer Mn(3,N)  ! 3 checks per bit
   integer edge_slot(7,M)
   integer synd(M)
   real tov(3,N)
   real toc(7,M)
   real tanhtoc(7,M)
   real zn(N),zsum(N),zsave(N,3)
   real llr(N)
   real Tmn
   integer(c_int) trace_enabled,trace_bp_iterations,trace_osd_calls
   integer(c_int) trace_bp_success,trace_osd_success
   integer(c_long_long) trace_total_started,trace_phase_started
   integer(c_long_long) trace_setup_us,trace_bp_llr_syndrome_us
   integer(c_long_long) trace_bp_bit_to_check_us,trace_bp_check_to_var_us,trace_osd_us
   integer(c_long_long) decode174_trace_elapsed_us

   interface
      integer(c_int) function wsjtx3_ldpc_trace_is_enabled() bind(C, name="wsjtx3_ldpc_trace_is_enabled")
        import :: c_int
      end function wsjtx3_ldpc_trace_is_enabled

      subroutine wsjtx3_ldpc_trace_add(bp_iterations,osd_calls,bp_success,osd_success,total_us,setup_us, &
           bp_llr_syndrome_us,bp_bit_to_check_us,bp_check_to_var_us,osd_us) bind(C, name="wsjtx3_ldpc_trace_add")
        import :: c_int,c_long_long
        integer(c_int), value :: bp_iterations,osd_calls,bp_success,osd_success
        integer(c_long_long), value :: total_us,setup_us,bp_llr_syndrome_us
        integer(c_long_long), value :: bp_bit_to_check_us,bp_check_to_var_us,osd_us
      end subroutine wsjtx3_ldpc_trace_add
   end interface

   include "ldpc_174_91_c_parity.f90"

   trace_enabled=wsjtx3_ldpc_trace_is_enabled()
   trace_bp_iterations=0
   trace_osd_calls=0
   trace_bp_success=0
   trace_osd_success=0
   trace_total_started=0
   trace_setup_us=0
   trace_bp_llr_syndrome_us=0
   trace_bp_bit_to_check_us=0
   trace_bp_check_to_var_us=0
   trace_osd_us=0
   if(trace_enabled.ne.0) call system_clock(count=trace_total_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   maxiterations=30
   nosd=0
   if(maxosd.gt.3) maxosd=3
   if(maxosd.eq.0) then ! osd with channel llrs
      nosd=1
      zsave(:,1)=llr
   elseif(maxosd.gt.0) then !
      nosd=maxosd
   elseif(maxosd.lt.0) then ! just bp
      nosd=0
   endif

   toc=0
   tov=0
   tanhtoc=0
! 每个校验边对应的变量槽在一次解码内保持不变，避免每轮 BP 重复查找。
   edge_slot=0
   do j=1,M
      do i=1,nrw(j)
         ibj=Nm(i,j)
         do kk=1,ncw
            if(Mn(kk,ibj).eq.j) then
               edge_slot(i,j)=kk
               exit
            endif
         enddo
      enddo
   enddo
! initialize messages to checks
   do j=1,M
      do i=1,nrw(j)
         toc(i,j)=llr((Nm(i,j)))
      enddo
   enddo
   if(trace_enabled.ne.0) trace_setup_us=decode174_trace_elapsed_us(trace_phase_started)

   ncnt=0
   nclast=0
   zsum=0.0
   do iter=0,maxiterations
      if(trace_enabled.ne.0) then
         trace_bp_iterations=trace_bp_iterations+1
         call system_clock(count=trace_phase_started)
      endif
! Update bit log likelihood ratios (tov=0 in iteration 0).
      do i=1,N
         if( apmask(i) .ne. 1 ) then
            zn(i)=llr(i)+sum(tov(1:ncw,i))
         else
            zn(i)=llr(i)
         endif
      enddo
      zsum=zsum+zn
      if(iter.gt.0 .and. iter.le.maxosd) then
         zsave(:,iter)=zsum
      endif

! Check to see if we have a codeword (check before we do any iteration).
      cw=0
      where( zn .gt. 0. ) cw=1
      ncheck=0
      do i=1,M
         synd(i)=sum(cw(Nm(1:nrw(i),i)))
         if( mod(synd(i),2) .ne. 0 ) ncheck=ncheck+1
      enddo
      if( ncheck .eq. 0 ) then ! we have a codeword - if crc is good, return it
         m96=0
         m96(1:77)=cw(1:77)
         m96(83:96)=cw(78:91)
         call get_crc14(m96,96,nbadcrc)
         nharderror=count( (2*cw-1)*llr .lt. 0.0 )
         if(nbadcrc.eq.0) then
            message91=cw(1:91)
            hdec=0
            where(llr .ge. 0) hdec=1
            nxor=ieor(hdec,cw)
            dmin=sum(nxor*abs(llr))
            ntype=1
            if(trace_enabled.ne.0) trace_bp_llr_syndrome_us=trace_bp_llr_syndrome_us+ &
                 decode174_trace_elapsed_us(trace_phase_started)
            go to 900
         endif
      endif
      if(trace_enabled.ne.0) trace_bp_llr_syndrome_us=trace_bp_llr_syndrome_us+ &
           decode174_trace_elapsed_us(trace_phase_started)

  if( iter.gt.0 ) then  ! this code block implements an early stopping criterion
!      if( iter.gt.10000 ) then  ! this code block implements an early stopping criterion
         nd=ncheck-nclast
         if( nd .lt. 0 ) then ! # of unsatisfied parity checks decreased
            ncnt=0  ! reset counter
         else
            ncnt=ncnt+1
         endif
!    write(*,*) iter,ncheck,nd,ncnt
         if( ncnt .ge. 5 .and. iter .ge. 10 .and. ncheck .gt. 15) then
            nharderror=-1
            exit
         endif
      endif
      nclast=ncheck

      if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
! Send messages from bits to check nodes
      do j=1,M
         do i=1,nrw(j)
            ibj=Nm(i,j)
            kk=edge_slot(i,j)
            toc(i,j)=zn(ibj)-tov(kk,ibj)
         enddo
      enddo
      if(trace_enabled.ne.0) trace_bp_bit_to_check_us=trace_bp_bit_to_check_us+ &
           decode174_trace_elapsed_us(trace_phase_started)

      if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
! send messages from check nodes to variable nodes
      do i=1,M
         tanhtoc(1:nrw(i),i)=tanh(-toc(1:nrw(i),i)/2)
      enddo

      do j=1,N
         do i=1,ncw
            ichk=Mn(i,j)  ! Mn(:,j) are the checks that include bit j
            Tmn=1.0
            do kk=1,nrw(ichk)
               if(Nm(kk,ichk).ne.j) Tmn=Tmn*tanhtoc(kk,ichk)
            enddo
            call platanh(-Tmn,y)
!      y=atanh(-Tmn)
            tov(i,j)=2*y
         enddo
      enddo
      if(trace_enabled.ne.0) trace_bp_check_to_var_us=trace_bp_check_to_var_us+ &
           decode174_trace_elapsed_us(trace_phase_started)

   enddo   ! bp iterations

   do i=1,nosd
      zn=zsave(:,i)
      if(trace_enabled.ne.0) then
         trace_osd_calls=trace_osd_calls+1
         call system_clock(count=trace_phase_started)
      endif
      call osd174_91(zn,Keff,apmask,norder,message91,cw,nharderror,dminosd)
      if(trace_enabled.ne.0) trace_osd_us=trace_osd_us+decode174_trace_elapsed_us(trace_phase_started)
      if(nharderror.gt.0) then
         hdec=0
         where(llr .ge. 0) hdec=1
         nxor=ieor(hdec,cw)
         dmin=sum(nxor*abs(llr))
         ntype=2
         trace_osd_success=1
         go to 900
      endif
   enddo

   ntype=0
   nharderror=-1
   dminosd=0.0

900 if(ntype.eq.1) trace_bp_success=1
   if(trace_enabled.ne.0) call wsjtx3_ldpc_trace_add(trace_bp_iterations,trace_osd_calls,trace_bp_success, &
        trace_osd_success,decode174_trace_elapsed_us(trace_total_started),trace_setup_us, &
        trace_bp_llr_syndrome_us,trace_bp_bit_to_check_us,trace_bp_check_to_var_us,trace_osd_us)
   return
end subroutine decode174_91

integer(kind=8) function decode174_trace_elapsed_us(started_at)
   use iso_c_binding, only: c_long_long
   integer(c_long_long), intent(in) :: started_at
   integer(c_long_long) finished_at,count_rate
   if(started_at.le.0) then
      decode174_trace_elapsed_us=0
      return
   endif
   call system_clock(count=finished_at,count_rate=count_rate)
   if(count_rate.le.0) then
      decode174_trace_elapsed_us=0
      return
   endif
   decode174_trace_elapsed_us=((finished_at-started_at)*1000000_c_long_long)/count_rate
end function decode174_trace_elapsed_us
