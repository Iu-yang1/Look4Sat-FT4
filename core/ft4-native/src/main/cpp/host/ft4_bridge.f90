module ft4_mobile_bridge
  use iso_c_binding
  use ft4_decode, only: ft4_decoder, ft4_decode_callback, decode
  implicit none

  integer, parameter :: MAX_CONTEXTS = 4
  integer, parameter :: MAX_RESULTS = 100
  integer, parameter :: FT4_NMAX = 21 * 3456
  integer, parameter :: FT4_SLOT_SAMPLES = 90000
  integer, parameter :: FT4_TONE_COUNT = 105
  integer, parameter :: FT4_CORE_TONES = 103

  type :: bridge_result
    real(c_float) :: sync = 0.0
    integer(c_int) :: snr = 0
    real(c_float) :: dt = 0.0
    real(c_float) :: freq = 0.0
    character(len=37) :: decoded = ' '
    integer(c_int) :: nap = 0
    real(c_float) :: qual = 0.0
  end type bridge_result

  type, bind(C) :: bridge_result_c
    integer(c_int) :: snr
    integer(c_int) :: nap
    real(c_float) :: sync
    real(c_float) :: dt
    real(c_float) :: freq
    real(c_float) :: qual
    character(kind=c_char) :: decoded(38)
  end type bridge_result_c

  type :: bridge_context
    logical :: active = .false.
    integer(c_int) :: sample_rate = 12000
    integer(c_int) :: expected_samples = FT4_SLOT_SAMPLES
    integer(c_long_long) :: utc_millis = 0
    integer(c_int) :: decode_depth = 3
    integer(c_int) :: qso_frequency_hz = 1500
    character(len=12) :: my_call = ''
    character(len=12) :: his_call = ''
    integer(c_int) :: result_count = 0
    type(bridge_result) :: results(MAX_RESULTS)
  end type bridge_context

  type(bridge_context), save :: contexts(MAX_CONTEXTS)
  type(ft4_decoder), save :: decoders(MAX_CONTEXTS)
  integer, save :: active_context = 0

contains

  logical function valid_handle(handle)
    integer(c_int), intent(in) :: handle
    valid_handle = handle >= 1 .and. handle <= MAX_CONTEXTS
    if (valid_handle) valid_handle = contexts(handle)%active
  end function valid_handle

  subroutine clear_results(handle)
    integer(c_int), intent(in) :: handle
    integer :: index
    contexts(handle)%result_count = 0
    do index = 1, MAX_RESULTS
      contexts(handle)%results(index)%sync = 0.0
      contexts(handle)%results(index)%snr = 0
      contexts(handle)%results(index)%dt = 0.0
      contexts(handle)%results(index)%freq = 0.0
      contexts(handle)%results(index)%decoded = ' '
      contexts(handle)%results(index)%nap = 0
      contexts(handle)%results(index)%qual = 0.0
    end do
  end subroutine clear_results

  subroutine clear_context(handle)
    integer(c_int), intent(in) :: handle
    if (handle < 1 .or. handle > MAX_CONTEXTS) return
    contexts(handle)%active = .false.
    contexts(handle)%sample_rate = 12000
    contexts(handle)%expected_samples = FT4_SLOT_SAMPLES
    contexts(handle)%utc_millis = 0
    contexts(handle)%decode_depth = 3
    contexts(handle)%qso_frequency_hz = 1500
    contexts(handle)%my_call = ''
    contexts(handle)%his_call = ''
    call clear_results(handle)
  end subroutine clear_context

  subroutine copy_c_string(source, destination)
    character(kind=c_char), intent(in) :: source(*)
    character(len=*), intent(out) :: destination
    integer :: index
    destination = ''
    do index = 1, len(destination)
      if (source(index) == c_null_char) exit
      destination(index:index) = source(index)
    end do
  end subroutine copy_c_string

  subroutine copy_fortran_string(source, destination)
    character(len=*), intent(in) :: source
    character(kind=c_char), intent(out) :: destination(*)
    integer :: index, copy_length
    copy_length = min(len_trim(source), 37)
    do index = 1, copy_length
      destination(index) = source(index:index)
    end do
    destination(copy_length + 1) = c_null_char
    do index = copy_length + 2, 38
      destination(index) = c_null_char
    end do
  end subroutine copy_fortran_string

  subroutine decode_callback(this, sync, snr, dt, freq, decoded, nap, qual)
    class(ft4_decoder), intent(inout) :: this
    real, intent(in) :: sync
    integer, intent(in) :: snr
    real, intent(in) :: dt
    real, intent(in) :: freq
    character(len=37), intent(in) :: decoded
    integer, intent(in) :: nap
    real, intent(in) :: qual
    integer :: next_index
    if (active_context < 1 .or. active_context > MAX_CONTEXTS) return
    if (.not. contexts(active_context)%active) return
    if (contexts(active_context)%result_count >= MAX_RESULTS) return
    next_index = contexts(active_context)%result_count + 1
    contexts(active_context)%results(next_index)%sync = sync
    contexts(active_context)%results(next_index)%snr = snr
    contexts(active_context)%results(next_index)%dt = dt
    contexts(active_context)%results(next_index)%freq = freq
    contexts(active_context)%results(next_index)%decoded = decoded
    contexts(active_context)%results(next_index)%nap = nap
    contexts(active_context)%results(next_index)%qual = qual
    contexts(active_context)%result_count = next_index
  end subroutine decode_callback

  integer(c_int) function ft4_bridge_create(sample_rate, expected_samples, utc_millis) &
      bind(C, name='ft4_bridge_create')
    integer(c_int), value :: sample_rate, expected_samples
    integer(c_long_long), value :: utc_millis
    integer :: handle
    ft4_bridge_create = 0
    if (sample_rate /= 12000 .or. expected_samples /= FT4_SLOT_SAMPLES) return
    do handle = 1, MAX_CONTEXTS
      if (.not. contexts(handle)%active) then
        call clear_context(handle)
        contexts(handle)%active = .true.
        contexts(handle)%utc_millis = utc_millis
        ft4_bridge_create = handle
        return
      end if
    end do
  end function ft4_bridge_create

  subroutine ft4_bridge_destroy(handle) bind(C, name='ft4_bridge_destroy')
    integer(c_int), value :: handle
    call clear_context(handle)
  end subroutine ft4_bridge_destroy

  subroutine ft4_bridge_reset(handle, utc_millis, expected_samples) bind(C, name='ft4_bridge_reset')
    integer(c_int), value :: handle, expected_samples
    integer(c_long_long), value :: utc_millis
    if (.not. valid_handle(handle)) return
    contexts(handle)%utc_millis = utc_millis
    contexts(handle)%expected_samples = expected_samples
    call clear_results(handle)
  end subroutine ft4_bridge_reset

  subroutine ft4_bridge_set_options(handle, decode_depth, qso_frequency_hz) &
      bind(C, name='ft4_bridge_set_options')
    integer(c_int), value :: handle, decode_depth, qso_frequency_hz
    if (.not. valid_handle(handle)) return
    contexts(handle)%decode_depth = max(1_c_int, min(3_c_int, decode_depth))
    contexts(handle)%qso_frequency_hz = max(0_c_int, min(3000_c_int, qso_frequency_hz))
  end subroutine ft4_bridge_set_options

  subroutine ft4_bridge_set_hints(handle, my_call, his_call) bind(C, name='ft4_bridge_set_hints')
    integer(c_int), value :: handle
    character(kind=c_char), intent(in) :: my_call(*), his_call(*)
    if (.not. valid_handle(handle)) return
    call copy_c_string(my_call, contexts(handle)%my_call)
    call copy_c_string(his_call, contexts(handle)%his_call)
  end subroutine ft4_bridge_set_hints

  integer(c_int) function ft4_bridge_process_float(handle, samples, sample_count) &
      bind(C, name='ft4_bridge_process_float')
    integer(c_int), value :: handle, sample_count
    real(c_float), intent(in) :: samples(*)
    integer(c_int16_t), allocatable :: iwave(:)
    integer :: index, copy_count
    ft4_bridge_process_float = -1
    if (.not. valid_handle(handle)) return
    if (sample_count /= FT4_SLOT_SAMPLES) return
    allocate(iwave(FT4_NMAX))
    iwave = 0
    copy_count = min(sample_count, FT4_NMAX)
    do index = 1, copy_count
      iwave(index) = int(max(-32767.0_c_float, min(32767.0_c_float, &
          samples(index) * 32767.0_c_float)), kind=c_int16_t)
    end do
    call clear_results(handle)
    active_context = handle
    call decode(decoders(handle), decode_callback, iwave, 0, &
        contexts(handle)%qso_frequency_hz, 0, 3000, contexts(handle)%decode_depth, &
        .false., 0, contexts(handle)%my_call, contexts(handle)%his_call)
    active_context = 0
    ft4_bridge_process_float = contexts(handle)%result_count
    deallocate(iwave)
  end function ft4_bridge_process_float

  integer(c_int) function ft4_bridge_get_result_count(handle) bind(C, name='ft4_bridge_get_result_count')
    integer(c_int), value :: handle
    ft4_bridge_get_result_count = 0
    if (valid_handle(handle)) ft4_bridge_get_result_count = contexts(handle)%result_count
  end function ft4_bridge_get_result_count

  integer(c_int) function ft4_bridge_get_result(handle, index, result) bind(C, name='ft4_bridge_get_result')
    integer(c_int), value :: handle, index
    type(bridge_result_c), intent(out) :: result
    integer :: result_index
    ft4_bridge_get_result = 0
    result%snr = 0
    result%nap = 0
    result%sync = 0.0
    result%dt = 0.0
    result%freq = 0.0
    result%qual = 0.0
    call copy_fortran_string('', result%decoded)
    if (.not. valid_handle(handle)) return
    result_index = index + 1
    if (result_index < 1 .or. result_index > contexts(handle)%result_count) return
    result%snr = contexts(handle)%results(result_index)%snr
    result%nap = contexts(handle)%results(result_index)%nap
    result%sync = contexts(handle)%results(result_index)%sync
    result%dt = contexts(handle)%results(result_index)%dt
    result%freq = contexts(handle)%results(result_index)%freq
    result%qual = contexts(handle)%results(result_index)%qual
    call copy_fortran_string(trim(contexts(handle)%results(result_index)%decoded), result%decoded)
    ft4_bridge_get_result = 1
  end function ft4_bridge_get_result

  integer(c_int) function ft4_bridge_generate_tones(message, tones, capacity) &
      bind(C, name='ft4_bridge_generate_tones')
    character(kind=c_char), intent(in) :: message(*)
    integer(c_int), intent(out) :: tones(*)
    integer(c_int), value :: capacity
    character(len=37) :: message_text, message_sent
    integer :: core_tones(FT4_CORE_TONES), message_bits(77), tone_index
    ft4_bridge_generate_tones = 0
    if (capacity < FT4_TONE_COUNT) return
    call copy_c_string(message, message_text)
    if (len_trim(message_text) == 0) return
    call genft4(message_text, 0, message_sent, message_bits, core_tones)
    if (index(message_sent, '*** bad message ***') > 0) return
    tones(1) = 0
    do tone_index = 1, FT4_CORE_TONES
      if (core_tones(tone_index) < 0 .or. core_tones(tone_index) > 3) return
      tones(tone_index + 1) = core_tones(tone_index)
    end do
    tones(FT4_TONE_COUNT) = 0
    ft4_bridge_generate_tones = FT4_TONE_COUNT
  end function ft4_bridge_generate_tones

  integer(c_int) function ft4_bridge_generate_wave(message, sample_rate, audio_frequency_hz, &
      output_wave, capacity) bind(C, name='ft4_bridge_generate_wave')
    character(kind=c_char), intent(in) :: message(*)
    integer(c_int), value :: sample_rate, capacity
    real(c_float), value :: audio_frequency_hz
    real(c_float), intent(out) :: output_wave(*)
    character(len=37) :: message_text, message_sent
    integer :: core_tones(FT4_CORE_TONES), message_bits(77)
    integer :: samples_per_symbol, wave_samples
    complex(c_float_complex), allocatable :: complex_wave(:)
    ft4_bridge_generate_wave = 0
    if (sample_rate /= 12000 .and. sample_rate /= 24000 .and. sample_rate /= 48000) return
    if (audio_frequency_hz < 0.0 .or. audio_frequency_hz + 3.0 * &
        real(sample_rate, c_float) / real(sample_rate * 48 / 1000, c_float) >= &
        0.5 * real(sample_rate, c_float)) return
    samples_per_symbol = sample_rate * 48 / 1000
    wave_samples = FT4_TONE_COUNT * samples_per_symbol
    if (capacity < wave_samples) return
    call copy_c_string(message, message_text)
    if (len_trim(message_text) == 0) return
    call genft4(message_text, 0, message_sent, message_bits, core_tones)
    if (index(message_sent, '*** bad message ***') > 0) return
    allocate(complex_wave(wave_samples))
    call gen_ft4wave(core_tones, FT4_CORE_TONES, samples_per_symbol, real(sample_rate), &
        real(audio_frequency_hz), complex_wave, output_wave, 0, wave_samples)
    deallocate(complex_wave)
    ft4_bridge_generate_wave = wave_samples
  end function ft4_bridge_generate_wave

end module ft4_mobile_bridge
