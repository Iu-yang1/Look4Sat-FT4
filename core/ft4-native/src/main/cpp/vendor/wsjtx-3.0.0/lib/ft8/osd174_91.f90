subroutine osd174_91(llr,k,apmask,ndeep,message91,cw,nhardmin,dmin)
!
! An ordered-statistics decoder for the (174,91) code.
! Message payload is 77 bits. Any or all of a 14-bit CRC can be
! used for detecting incorrect codewords. The remaining CRC bits are
! cascaded with the LDPC code for the purpose of improving the
! distance spectrum of the code.
!
! If p1 (0.le.p1.le.14) is the number of CRC14 bits that are
! to be used for bad codeword detection, then the argument k should
! be set to 77+p1.
!
! Valid values for k are in the range [77,91].
!
   use iso_c_binding, only: c_float,c_int,c_int8_t,c_long_long
   integer, parameter:: N=174
   integer*1 apmask(N),apmaskr(N)
   integer*1, allocatable, save :: gen(:,:)
   integer*1, allocatable :: genmrb(:,:),g2(:,:)
   integer*1, allocatable :: temp(:),m0(:),me(:),mi(:),misub(:),e2sub(:),e2(:),ui(:)
   integer*1, allocatable :: r2pat(:)
   integer(c_int) indices(N)
   integer nxor(N)
   integer*1 cw(N),ce(N),cebase(N),c0(N),hdec(N)
   integer*1, allocatable :: decoded(:)
   integer*1 message91(91),m96(96)
   integer indx(N)
   real llr(N),rx(N),absrx(N)

   logical first,reset
   integer(c_int) trace_enabled,trace_success
   integer(c_int) gaussian_status,order1_status
   integer(c_long_long) trace_total_started,trace_phase_started
   integer(c_long_long) trace_allocation_init_us,trace_generator_init_us
   integer(c_long_long) trace_input_prepare_us,trace_sort_us,trace_matrix_copy_us
   integer(c_long_long) trace_gaussian_elim_us,trace_matrix_permute_us,trace_order0_us
   integer(c_long_long) trace_order1_search_us,trace_higher_order_search_us
   integer(c_long_long) trace_second_preprocess_us,trace_validation_us
   integer(c_long_long) osd174_trace_elapsed_us
   data first/.true./
   save first

   interface
      integer(c_int) function wsjtx3_osd_trace_is_enabled() bind(C, name="wsjtx3_osd_trace_is_enabled")
        import :: c_int
      end function wsjtx3_osd_trace_is_enabled

      subroutine wsjtx3_osd_trace_add(success,total_us,allocation_init_us,generator_init_us,input_prepare_us, &
           sort_us,matrix_copy_us,gaussian_elim_us,matrix_permute_us,order0_us,order1_search_us, &
           higher_order_search_us,second_preprocess_us,validation_us) bind(C, name="wsjtx3_osd_trace_add")
        import :: c_int,c_long_long
        integer(c_int), value :: success
        integer(c_long_long), value :: total_us,allocation_init_us,generator_init_us,input_prepare_us,sort_us
        integer(c_long_long), value :: matrix_copy_us,gaussian_elim_us,matrix_permute_us,order0_us
        integer(c_long_long), value :: order1_search_us,higher_order_search_us,second_preprocess_us,validation_us
      end subroutine wsjtx3_osd_trace_add

      integer(c_int) function wsjtx3_osd_gaussian_eliminate(matrix,k,n,indices) &
           bind(C, name="wsjtx3_osd_gaussian_eliminate")
        import :: c_int,c_int8_t
        integer(c_int8_t) :: matrix(*)
        integer(c_int), value :: k,n
        integer(c_int) :: indices(*)
      end function wsjtx3_osd_gaussian_eliminate

      integer(c_int) function wsjtx3_osd_order1_search(c0,g2,hdec,m0,apmask,absrx,k,n,nt,ntheta, &
           include_pre1,cw,nhardmin,dmin) bind(C, name="wsjtx3_osd_order1_search")
        import :: c_float,c_int,c_int8_t
        integer(c_int8_t) :: c0(*),g2(*),hdec(*),m0(*),apmask(*),cw(*)
        real(c_float) :: absrx(*),dmin
        integer(c_int), value :: k,n,nt,ntheta,include_pre1
        integer(c_int) :: nhardmin
      end function wsjtx3_osd_order1_search
   end interface

   trace_enabled=wsjtx3_osd_trace_is_enabled()
   trace_success=0
   trace_total_started=0
   trace_allocation_init_us=0
   trace_generator_init_us=0
   trace_input_prepare_us=0
   trace_sort_us=0
   trace_matrix_copy_us=0
   trace_gaussian_elim_us=0
   trace_matrix_permute_us=0
   trace_order0_us=0
   trace_order1_search_us=0
   trace_higher_order_search_us=0
   trace_second_preprocess_us=0
   trace_validation_us=0
   if(trace_enabled.ne.0) then
      call system_clock(count=trace_total_started)
      call system_clock(count=trace_phase_started)
   endif
   allocate( genmrb(k,N), g2(N,k) )
   allocate( temp(k), m0(k), me(k), mi(k), misub(k), e2sub(N-k), e2(N-k), ui(N-k) )
   allocate( r2pat(N-k), decoded(k) )
   if(trace_enabled.ne.0) trace_allocation_init_us=osd174_trace_elapsed_us(trace_phase_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   if( first ) then ! fill the generator matrix
!
! Create generator matrix for partial CRC cascaded with LDPC code.
!
! Let p2=91-k and p1+p2=14.
!
! The last p2 bits of the CRC14 are cascaded with the LDPC code.
!
! The first p1=k-77 CRC14 bits will be used for error detection.
!
      allocate( gen(k,N) )
      gen=0
      do i=1,k
         message91=0
         message91(i)=1
         if(i.le.77) then
            m96=0
            m96(1:91)=message91
            call get_crc14(m96,96,ncrc14)
            do j=1,14
               message91(77+j)=ibits(ncrc14,14-j,1)
            enddo
            message91(78:k)=0
         endif
         call encode174_91_nocrc(message91,cw)
         gen(i,:)=cw
      enddo

      first=.false.
   endif
   if(trace_enabled.ne.0) trace_generator_init_us=osd174_trace_elapsed_us(trace_phase_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   rx=llr
   apmaskr=apmask

! Hard decisions on the received word.
   hdec=0
   where(rx .ge. 0) hdec=1

! Use magnitude of received symbols as a measure of reliability.
   absrx=abs(rx)
   if(trace_enabled.ne.0) trace_input_prepare_us=osd174_trace_elapsed_us(trace_phase_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   call indexx(absrx,N,indx)
   if(trace_enabled.ne.0) trace_sort_us=osd174_trace_elapsed_us(trace_phase_started)

! Re-order the columns of the generator matrix in order of decreasing reliability.
   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   do i=1,N
      genmrb(1:k,i)=gen(1:k,indx(N+1-i))
      indices(i)=indx(N+1-i)
   enddo
   if(trace_enabled.ne.0) trace_matrix_copy_us=osd174_trace_elapsed_us(trace_phase_started)

! Do gaussian elimination to create a generator matrix with the most reliable
! received bits in positions 1:k in order of decreasing reliability (more or less).
   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   gaussian_status=wsjtx3_osd_gaussian_eliminate(genmrb,k,N,indices)
   if(gaussian_status.eq.0) then
      do id=1,k ! diagonal element indices
         do icol=id,k+20  ! The 20 is ad hoc - beware
            iflag=0
            if( genmrb(id,icol) .eq. 1 ) then
               iflag=1
               if( icol .ne. id ) then ! reorder column
                  temp(1:k)=genmrb(1:k,id)
                  genmrb(1:k,id)=genmrb(1:k,icol)
                  genmrb(1:k,icol)=temp(1:k)
                  itmp=indices(id)
                  indices(id)=indices(icol)
                  indices(icol)=itmp
               endif
               do ii=1,k
                  if( ii .ne. id .and. genmrb(ii,id) .eq. 1 ) then
                     genmrb(ii,1:N)=ieor(genmrb(ii,1:N),genmrb(id,1:N))
                  endif
               enddo
               exit
            endif
         enddo
      enddo
   endif
   if(trace_enabled.ne.0) trace_gaussian_elim_us=osd174_trace_elapsed_us(trace_phase_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   g2=transpose(genmrb)

! The hard decisions for the k MRB bits define the order 0 message, m0.
! Encode m0 using the modified generator matrix to find the "order 0" codeword.
! Flip various combinations of bits in m0 and re-encode to generate a list of
! codewords. Return the member of the list that has the smallest Euclidean
! distance to the received word.

   hdec=hdec(indices)   ! hard decisions from received symbols
   m0=hdec(1:k)         ! zero'th order message
   absrx=absrx(indices)
   rx=rx(indices)
   apmaskr=apmaskr(indices)
   if(trace_enabled.ne.0) trace_matrix_permute_us=osd174_trace_elapsed_us(trace_phase_started)

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   call mrbencode91(m0,c0,g2,N,k)
   nxor=ieor(c0,hdec)
   nhardmin=sum(nxor)
   dmin=sum(nxor*absrx)

   cw=c0
   ntotal=0
   nrejected=0
   npre1=0
   npre2=0
   if(trace_enabled.ne.0) trace_order0_us=osd174_trace_elapsed_us(trace_phase_started)

   if(ndeep.eq.0) goto 998  ! norder=0
   if(ndeep.gt.6) ndeep=6
   if( ndeep.eq. 1) then
      nord=1
      npre1=0
      npre2=0
      nt=40
      ntheta=12
   elseif(ndeep.eq.2) then
      nord=1
      npre1=1
      npre2=0
      nt=40
!      ntheta=12
      ntheta=10
   elseif(ndeep.eq.3) then
      nord=1
      npre1=1
      npre2=1
      nt=40
      ntheta=12
      ntau=14
   elseif(ndeep.eq.4) then
      nord=2
      npre1=1
      npre2=1
      nt=40
      ntheta=12
      ntau=17
   elseif(ndeep.eq.5) then
      nord=3
      npre1=1
      npre2=1
      nt=40
      ntheta=12
      ntau=15
   else                     !ndeep=6
      nord=4
      npre1=1
      npre2=1
      nt=95
      ntheta=12
      ntau=15
   endif

   order1_status=0
   if(nord.eq.1) then
      if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
      order1_status=wsjtx3_osd_order1_search(c0,g2,hdec,m0,apmaskr,absrx,k,N,nt,ntheta,npre1, &
           cw,nhardmin,dmin)
      if(trace_enabled.ne.0) trace_order1_search_us=osd174_trace_elapsed_us(trace_phase_started)
   endif

   if(order1_status.eq.0) then
      do iorder=1,nord
      if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
      misub(1:k-iorder)=0
      misub(k-iorder+1:k)=1
      iflag=k-iorder+1
      do while(iflag .ge.0)
         ! Use linear-code row XORs instead of repeatedly encoding one test pattern.
         cebase=c0
         do i=iflag,k
            if(misub(i).eq.1) cebase=ieor(cebase,g2(1:N,i))
         enddo
         me=ieor(m0,misub)
         if(iorder.eq.nord .and. npre1.eq.0) then
            iend=iflag
         else
            iend=1
         endif
         d1=0.
         do n1=iflag,iend,-1
            mi=misub
            mi(n1)=1
            if(any(iand(apmaskr(1:k),mi).eq.1)) cycle
            ntotal=ntotal+1
            if(n1.eq.iflag) then
               ce=cebase
               e2sub=ieor(ce(k+1:N),hdec(k+1:N))
               e2=e2sub
               nd1kpt=sum(e2sub(1:nt))+1
               d1=sum(ieor(me(1:k),hdec(1:k))*absrx(1:k))
            else
               ce=ieor(cebase,g2(1:N,n1))
               e2=ieor(e2sub,g2(k+1:N,n1))
               nd1kpt=sum(e2(1:nt))+2
            endif
            if(nd1kpt .le. ntheta) then
               nxor=ieor(ce,hdec)
               if(n1.eq.iflag) then
                  dd=d1+sum(e2sub*absrx(k+1:N))
               else
                  dd=d1+ieor(ce(n1),hdec(n1))*absrx(n1)+sum(e2*absrx(k+1:N))
               endif
               if( dd .lt. dmin ) then
                  dmin=dd
                  cw=ce
                  nhardmin=sum(nxor)
                  nd1kptbest=nd1kpt
               endif
            else
               nrejected=nrejected+1
            endif
         enddo
! Get the next test error pattern, iflag will go negative
! when the last pattern with weight iorder has been generated.
         call nextpat91(misub,k,iorder,iflag)
      enddo
      if(trace_enabled.ne.0) then
         if(iorder.eq.1) then
            trace_order1_search_us=trace_order1_search_us+osd174_trace_elapsed_us(trace_phase_started)
         else
            trace_higher_order_search_us=trace_higher_order_search_us+osd174_trace_elapsed_us(trace_phase_started)
         endif
      endif
      enddo
   endif

   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
   if(npre2.eq.1) then
      reset=.true.
      ntotal=0
      do i1=k,1,-1
         do i2=i1-1,1,-1
            ntotal=ntotal+1
            mi(1:ntau)=ieor(g2(k+1:k+ntau,i1),g2(k+1:k+ntau,i2))
            call boxit91(reset,mi(1:ntau),ntau,ntotal,i1,i2)
         enddo
      enddo

      ncount2=0
      ntotal2=0
      reset=.true.
! Now run through again and do the second pre-processing rule
      misub(1:k-nord)=0
      misub(k-nord+1:k)=1
      iflag=k-nord+1
      do while(iflag .ge.0)
         me=ieor(m0,misub)
         call mrbencode91(me,ce,g2,N,k)
         e2sub=ieor(ce(k+1:N),hdec(k+1:N))
         do i2=0,ntau
            ntotal2=ntotal2+1
            ui=0
            if(i2.gt.0) ui(i2)=1
            r2pat=ieor(e2sub,ui)
778         continue
            call fetchit91(reset,r2pat(1:ntau),ntau,in1,in2)
            if(in1.gt.0.and.in2.gt.0) then
               ncount2=ncount2+1
               mi=misub
               mi(in1)=1
               mi(in2)=1
               if(sum(mi).lt.nord+npre1+npre2.or.any(iand(apmaskr(1:k),mi).eq.1)) cycle
               me=ieor(m0,mi)
               call mrbencode91(me,ce,g2,N,k)
               nxor=ieor(ce,hdec)
               dd=sum(nxor*absrx)
               if( dd .lt. dmin ) then
                  dmin=dd
                  cw=ce
                  nhardmin=sum(nxor)
               endif
               goto 778
            endif
         enddo
         call nextpat91(misub,k,nord,iflag)
      enddo
   endif
   if(trace_enabled.ne.0) trace_second_preprocess_us=osd174_trace_elapsed_us(trace_phase_started)

998 continue
   if(trace_enabled.ne.0) call system_clock(count=trace_phase_started)
! Re-order the codeword to [message bits][parity bits] format.
   cw(indices)=cw
   hdec(indices)=hdec
   message91=cw(1:91)
   m96=0
   m96(1:77)=cw(1:77)
   m96(83:96)=cw(78:91)
   call get_crc14(m96,96,nbadcrc)
   if(nbadcrc.ne.0) nhardmin=-nhardmin
   if(trace_enabled.ne.0) then
      trace_validation_us=osd174_trace_elapsed_us(trace_phase_started)
      if(nhardmin.gt.0) trace_success=1
      call wsjtx3_osd_trace_add(trace_success,osd174_trace_elapsed_us(trace_total_started), &
           trace_allocation_init_us,trace_generator_init_us,trace_input_prepare_us,trace_sort_us, &
           trace_matrix_copy_us,trace_gaussian_elim_us,trace_matrix_permute_us,trace_order0_us, &
           trace_order1_search_us,trace_higher_order_search_us,trace_second_preprocess_us,trace_validation_us)
   endif

   return
end subroutine osd174_91

integer(kind=8) function osd174_trace_elapsed_us(started_at)
   use iso_c_binding, only: c_long_long
   integer(c_long_long), intent(in) :: started_at
   integer(c_long_long) finished_at,count_rate
   if(started_at.le.0) then
      osd174_trace_elapsed_us=0
      return
   endif
   call system_clock(count=finished_at,count_rate=count_rate)
   if(count_rate.le.0) then
      osd174_trace_elapsed_us=0
      return
   endif
   osd174_trace_elapsed_us=((finished_at-started_at)*1000000_c_long_long)/count_rate
end function osd174_trace_elapsed_us

subroutine mrbencode91(me,codeword,g2,N,K)
   integer*1 me(K),codeword(N),g2(N,K)
! fast encoding for low-weight test patterns
   codeword=0
   do i=1,K
      if( me(i) .eq. 1 ) then
         codeword=ieor(codeword,g2(1:N,i))
      endif
   enddo
   return
end subroutine mrbencode91

subroutine nextpat91(mi,k,iorder,iflag)
   integer*1 mi(k),ms(k)
! generate the next test error pattern
   ind=-1
   do i=1,k-1
      if( mi(i).eq.0 .and. mi(i+1).eq.1) ind=i
   enddo
   if( ind .lt. 0 ) then ! no more patterns of this order
      iflag=ind
      return
   endif
   ms=0
   ms(1:ind-1)=mi(1:ind-1)
   ms(ind)=1
   ms(ind+1)=0
   if( ind+1 .lt. k ) then
      nz=iorder-sum(ms)
      ms(k-nz+1:k)=1
   endif
   mi=ms
   do i=1,k  ! iflag will point to the lowest-index 1 in mi
      if(mi(i).eq.1) then
         iflag=i
         exit
      endif
   enddo
   return
end subroutine nextpat91

subroutine boxit91(reset,e2,ntau,npindex,i1,i2)
   integer*1 e2(1:ntau)
   integer   indexes(5000,2),fp(0:525000),np(5000)
   logical reset
   common/boxes/indexes,fp,np

   if(reset) then
      patterns=-1
      fp=-1
      np=-1
      sc=-1
      indexes=-1
      reset=.false.
   endif

   indexes(npindex,1)=i1
   indexes(npindex,2)=i2
   ipat=0
   do i=1,ntau
      if(e2(i).eq.1) then
         ipat=ipat+ishft(1,ntau-i)
      endif
   enddo

   ip=fp(ipat)   ! see what's currently stored in fp(ipat)
   if(ip.eq.-1) then
      fp(ipat)=npindex
   else
      do while (np(ip).ne.-1)
         ip=np(ip)
      enddo
      np(ip)=npindex
   endif
   return
end subroutine boxit91

subroutine fetchit91(reset,e2,ntau,i1,i2)
   integer   indexes(5000,2),fp(0:525000),np(5000)
   integer   lastpat
   integer*1 e2(ntau)
   logical reset
   common/boxes/indexes,fp,np
   save lastpat,inext

   if(reset) then
      lastpat=-1
      reset=.false.
   endif

   ipat=0
   do i=1,ntau
      if(e2(i).eq.1) then
         ipat=ipat+ishft(1,ntau-i)
      endif
   enddo
   index=fp(ipat)

   if(lastpat.ne.ipat .and. index.gt.0) then ! return first set of indices
      i1=indexes(index,1)
      i2=indexes(index,2)
      inext=np(index)
   elseif(lastpat.eq.ipat .and. inext.gt.0) then
      i1=indexes(inext,1)
      i2=indexes(inext,2)
      inext=np(inext)
   else
      i1=-1
      i2=-1
      inext=-1
   endif
   lastpat=ipat
   return
end subroutine fetchit91
