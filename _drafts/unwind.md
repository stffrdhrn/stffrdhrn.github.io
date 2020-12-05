---
title: Unwinding - How the C++ Exceptions Work
layout: post
date: 2020-11-01
---

I have been working on porting GLIBC to the OpenRISC architecture.  This
has taken me quite a bit longer than I anticipated as with GLIBC upstreaming
you need to get every single test to pass.  This was different compared with
GDB and GCC which were a bit more lenient.

After my first upstreaming attempt which was completey tested on simulators.
I have moved over to an FPGA environment running Linux on OpenRISC mor1kx
debugging over an SSH session.  To get to where I am now this required:

 - Fixing buggy network drivers in Litex
 - Updating the OpenRISC GDB port to support gdbserver, and also native debugging
 - Fixing bugs in the Kernel and GCC to get gdbserver and native suport working

Why do I need such a good debugging environment?  Because some tests were failing,
nasty C++ unwinder tests.  In order to catch the bugs in their tracks I wanted
to have a good debug environment.


## A Bug

The bug I am trying to trace is a case where C++ exceptions thrown in signal
handlers are not calling the C++ catch blocks and simply exiting.

This is the glibc test case `nptl/tst-cancel24`. Basically the test will create
a child thread that waits on a semaphore and cancel it.  It is expected
that the child thread when cancelled in the semaphore will call its catch blocks.

In this case `except_caught` should get set to `true`, if not the test fails.

```c
static void *
tf (void *arg) {
  sem_t *s = static_cast<sem_t *> (arg);

  try {
      monitor m;

      pthread_barrier_wait (&b);

      while (1)
        sem_wait (s);
  } catch (...) {
      except_caught = true;
      throw;
  }
  return NULL;
}
```

With GDB we can trace right to the start of the exception handling logic.  This
is right before we start unwinding stacks.

Stack unwinding is started with one of 2 functions:
 - `_Unwind_ForcedUnwind` - called when our thread gets a stopping signal
 - `_Unwind_RaiseException` - called when we want to raise an exception

In our case we can start at `_Unwind_ForcedUnwind`, so I set a gdb
breakpoint there.

This is the stack trace I have now:

```
#0  _Unwind_ForcedUnwind_Phase2 (exc=0x30caf658, context=0x30caeb6c, frames_p=0x30caea90) at ../../../libgcc/unwind.inc:192
#1  0x30303858 in _Unwind_ForcedUnwind (exc=0x30caf658, stop=0x30321dcc <unwind_stop>, stop_argument=0x30caeea4) at ../../../libgcc/unwind.inc:217
#2  0x30321fc0 in __GI___pthread_unwind (buf=<optimized out>) at unwind.c:121
#3  0x30312388 in __do_cancel () at pthreadP.h:313
#4  sigcancel_handler (sig=32, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:162
#5  sigcancel_handler (sig=<optimized out>, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:127
#6  <signal handler called>
#7  0x303266d0 in __futex_abstimed_wait_cancelable64 (futex_word=0x7ffffd78, expected=1, clockid=<optimized out>, abstime=0x0, private=<optimized out>)
    at ../sysdeps/nptl/futex-internal.c:66
#8  0x303210f8 in __new_sem_wait_slow64 (sem=0x7ffffd78, abstime=0x0, clockid=0) at sem_waitcommon.c:285
#9  0x00002884 in tf (arg=0x7ffffd78) at throw-pthread-sem.cc:35
#10 0x30314548 in start_thread (arg=<optimized out>) at pthread_create.c:463
#11 0x3043638c in __or1k_clone () from /lib/libc.so.6
Backtrace stopped: frame did not save the PC
(gdb)
```

# So what is `_Unwind_ForcedUnwind`?

For this case we need to understand how unwinding works when a thread is
cancelled in a multithreaded
([pthread](https://en.wikipedia.org/wiki/POSIX_Threads)) glibc environment.  I
will cover this path as this is where the bug was occuring and it's a bit more
interesting.  Unwinding with regular c++ *throw* and *catch* uses a very similar
mechanism.

There are 3 contributors to unwinding are:

 - GLIBC - provides the pthread runtime and cleanup callbacks to the GCC unwinder code
 - GCC - `libgcc_s.so` - handles reading dwarf and doing the frame decoding
 - Our Program and Libraries - provide the `.eh_frame` **DWARF** metadata

 GLIBC
  - [sigcancel_handler](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/nptl-init.c;h=53b817715d58192857ed14450052e16dc34bc01b;hb=HEAD#l126) -
    Setup during the pthread runtime initialization, it handles cancellation,
    which calls `__do_cancel()`, which calls `__pthread_unwind()`.
  - [`__pthread_unwind`](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/unwind.c;h=8f157e49f4a088ac64722e85ff24514fff7f3c71;hb=HEAD#l121) -
    Is called with `pd->cleanup_jmp_buf`.  It calls glibc's `__Unwind_ForcedUnwind`.
  - [`_Unwind_ForcedUnwind`](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/nptl/unwind-forcedunwind.c;h=50a089282bc236aa644f40feafd0dacdafe3a4e7;hb=HEAD#l122) - 
    Loads GCC's `libgcc_s.so` version of `_Unwind_ForcedUnwind`
    and calls it with parameters:
    - `exc` - the exception context
    - `unwind_stop` - the stop function, called for each frame of the unwind, with
      the stop argument `ibuf`
    - `ibuf` - the `jmp_buf`, created by `setjmp` (`self->cleanup_jmp_buf`) in `pthread_create`
      the unwinder can call this to return back to pthread_create to do
      the cleanup
  - [unwind_stop](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/unwind.c;h=8f157e49f4a088ac64722e85ff24514fff7f3c71;hb=HEAD#l39) -
    Checks the current state of unwind and call the `cancel_jmp_buf` if
    we are at the end of stack.  When the `cancel_jmp_buf` is called the thread
    exits.

About the `pd->cancel_jmp_buf`.  This is setup during
[pthread_create](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/pthread_create.c;h=bad4e57a845bd3148ad634acaaccbea08b04dbbd;hb=HEAD#l406)
using the [setjmp](https://www.man7.org/linux/man-pages/man3/setjmp.3.html) and
[longjump](https://man7.org/linux/man-pages/man3/longjmp.3.html) non local goto mechanism.

A highly redacted version of our `pthread_create` and `unwind_stop` functions is
shown below, to illustrate the interaction of `setjmp`/`longjmp`:

```c
start_thread()
{
  struct pthread *pd = START_THREAD_SELF;
  ...
  struct pthread_unwind_buf unwind_buf;

  int not_first_call;
  not_first_call = setjmp ((struct __jmp_buf_tag *) unwind_buf.cancel_jmp_buf);
  ...
  if (__glibc_likely (! not_first_call))
    {
      /* Store the new cleanup handler info.  */
      THREAD_SETMEM (pd, cleanup_jmp_buf, &unwind_buf);
      ...
      ret = pd->start_routine (pd->arg);
      THREAD_SETMEM (pd, result, ret);
    }
  ... free resources ...
  __exit_thread ();
}
```

```c
unwind_stop (_Unwind_Action actions,
	     struct _Unwind_Context *context, void *stop_parameter)
{
  struct pthread_unwind_buf *buf = stop_parameter;
  struct pthread *self = THREAD_SELF;
  int do_longjump = 0;
  ...

  if ((actions & _UA_END_OF_STACK)
      || ... )
    do_longjump = 1;

  ...
  if (do_longjump)
    __libc_unwind_longjmp ((struct __jmp_buf_tag *) buf->cancel_jmp_buf, 1);

  return _URC_NO_REASON;
}




  GCC
   - In file [libgcc/unwind.inc](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;hb=HEAD)
     - **DWARF** implementations [libgcc/unwind-dw2.c](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;hb=HEAD)
     - **FDE** lookup code [libgcc.unwind-dw2-fce.c](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2-fde.c;hb=HEAD)

   - `_Unwind_ForcedUnwind` - calls:
     - uw_init_context()           - load details of the current frame.
     - _Unwind_ForcedUnwind_Phase2 - do the frame iterations
     - uw_install_context()        - actually context switch to the final frame.

   - `_Unwind_ForcedUnwind_Phase2` - loops forever doing:
     - uw_frame_state_for() - setup FS for the current context (one frame up)
     - stop(1, action, exc, context, stop_argument)
     - fs.personality (1, exc, context)
     - uw_advance_context () - move current context one frame up
     - _Unwind_Frames_Increment () - just does frames++,

   - DWARF, the followig methods are implemented using the dwarf (there are other implementations
     in GCC, we and most architectures now use DWRF).
     - uw_init_context FDE frame description entry
     - uw_install_context
     - uw_frame_state_for
     - uw_advance_context

Personality
  - The personality routing is the interface between the unwind routines and the
    c++ (or other language) runtime, which handles the exception handling
    logic for that language.

  - C++
    [__gxx_personality_v0](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libstdc%2B%2B-v3/libsupc%2B%2B/eh_personality.cc;h=fd7cd6fc79886bf17aea6bc713d2a3840aa31326;hb=HEAD#l336)

    In C++ the personality routine is executed for each stack frame.  The
    function checks if there is a catch block that matches the exception being
    thrown.  If there is, it will update the context to prepare it to jump
    into the catch routine returning `_URC_INSTALL_CONTEXT`.
    If there is no catch block matching it returns `_URC_CONTINUE_UNWIND`.

    In the case of `_URC_INSTALL_CONTEXT` then the `_Unwind_ForcedUnwind_Phase2`
    loop breaks and calls `uw_install_context`.

glibc/nptl/unwind.c

glibc/sysdeps/nptl/unwind-forcedunwind.c


```c
_Unwind_Reason_Code
_Unwind_ForcedUnwind (struct _Unwind_Exception *exc, _Unwind_Stop_Fn stop,
                      void *stop_argument)
{
  if (__glibc_unlikely (libgcc_s_handle == NULL))
    pthread_cancel_init ();
  else
    atomic_read_barrier ();

  _Unwind_Reason_Code (*forcedunwind)
    (struct _Unwind_Exception *, _Unwind_Stop_Fn, void *)
    = libgcc_s_forcedunwind;
  PTR_DEMANGLE (forcedunwind);
  return forcedunwind (exc, stop, stop_argument);
}
```

## Unwinding through sig hanlder

sigcancel_handler - libc
(setup_rt_frame ) - kernel

 frame
 - info - copied from ksig
 - uc - link  - 0
     - flags - 0
     - stack - sp  (of before sig sent, i.e. caller of syscall)
     - mc->regs  - regs
     - mask
     - retcode - trampoline
        l.ori r11,r0,__NR_sigreturn
        l.sys 1

 - regs - pc  - sig-handler
       - r9  - trampoline - to be called after sig-handler
       - r3  - arg - ksig->sig
       - r4  - arg - info
       - r5  - arg - uc


Stack - in sig handler
```
s0 < func calls syscall >
   / (sig frame)
s1 | info
   | uc
   |  - stack sp
   \
   / sighandler
s2 |  sp -  frame
   |  r9 -  trampoline
   \
```

Stack - in trampoline
```
s0 < func calls syscall >
   /
s1 |/ trampoline
   |\  sp - sp
   \
  { rt_sigreturn       }
```

After the sighandler action code returns we return and run `r9`, which calls sigreturn.

  - restore blocked flags (from before syscall)
  - restore mcontext
  - restore altstack
    - return r11

So,
In the end it looks like the eh_frame for libpthread was missing a lot of eh_frame
date.
Who create the eh_frame anyway?  GCC or Binutils (Assembler). If we run GCC
with the `-S` argument we can see GCC will output `.cfi` directives.


```c
         .file   "unwind.c"
        .section        .text
        .align 4
        .type   unwind_stop, @function
unwind_stop:
.LFB83:
        .cfi_startproc
        l.addi  r1, r1, -28
        .cfi_def_cfa_offset 28
        l.sw    0(r1), r16
        l.sw    4(r1), r18
        l.sw    8(r1), r20
        l.sw    12(r1), r22
        l.sw    16(r1), r24
        l.sw    20(r1), r26
        l.sw    24(r1), r9
        .cfi_offset 16, -28
        .cfi_offset 18, -24
        .cfi_offset 20, -20
        .cfi_offset 22, -16
        .cfi_offset 24, -12
        .cfi_offset 26, -8
        .cfi_offset 9, -4
        l.or    r24, r8, r8
        l.or    r22, r10, r10
        l.lwz   r18, -1172(r10)
        l.lwz   r20, -692(r10)
        l.lwz   r17, -688(r10)
        l.add   r20, r20, r17
        l.andi  r16, r4, 16
        l.sfnei r16, 0
```

The eh_frame is missing for futex-internal. The one were unwinding is failing
we find it was completely mising `.cfi` directives.  This means something is
wrong with GCC.


```c
        .file   "futex-internal.c"
        .section        .text
        .section        .rodata.str1.1,"aMS",@progbits,1
.LC0:
        .string "The futex facility returned an unexpected error code.\n"
        .section        .text
        .align 4
        .global __futex_abstimed_wait_cancelable64
        .type   __futex_abstimed_wait_cancelable64, @function
__futex_abstimed_wait_cancelable64:
        l.addi  r1, r1, -20
        l.sw    0(r1), r16
        l.sw    4(r1), r18
        l.sw    8(r1), r20
        l.sw    12(r1), r22
        l.sw    16(r1), r9
        l.or    r22, r3, r3
        l.or    r20, r4, r4
        l.or    r16, r6, r6
        l.sfnei r6, 0
        l.ori   r17, r0, 1
        l.cmov  r17, r17, r0
        l.sfeqi r17, 0
        l.bnf   .L14
         l.nop
```

Looking closer at the build line of these 2 files I see the build of `futex-internal.c`
is missing `-fexceptions`.

This flag is needed to enabled the `eh_frames` section, which is what powers c++
exceptions, it is needed even when we are building C in our case as we C++ exceptions
may need to unwind through C function stack frames.

So why is it not enabled?  Is this a problem with the GLIBC build?

Looking at GLIBC the `nptl/Makefile` set's `-fexceptions` explicitly for each
c file that needs it.  For example:

```
# The following are cancellation points.  Some of the functions can
# block and therefore temporarily enable asynchronous cancellation.
# Those must be compiled asynchronous unwind tables.
CFLAGS-pthread_testcancel.c += -fexceptions
CFLAGS-pthread_join.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_timedjoin.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_clockjoin.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_once.c += $(uses-callbacks) -fexceptions \
                        -fasynchronous-unwind-tables
CFLAGS-pthread_cond_wait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_wait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_timedwait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_clockwait.c = -fexceptions -fasynchronous-unwind-tables
```

If is missing such a line for `futex-internal.c`.  The following patch and a
libpthread rebuild fixes the issue!


```
--- a/nptl/Makefile
+++ b/nptl/Makefile
@@ -220,6 +220,7 @@ CFLAGS-pthread_cond_wait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_wait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_timedwait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_clockwait.c = -fexceptions -fasynchronous-unwind-tables
+CFLAGS-futex-internal.c += -fexceptions -fasynchronous-unwind-tables

 # These are the function wrappers we have to duplicate here.
 CFLAGS-fcntl.c += -fexceptions -fasynchronous-unwind-tables
```

## Additional Reading
 - The IA64 Undwinder ABI - https://itanium-cxx-abi.github.io/cxx-abi/

OR1K

```
#0  or1k_fallback_frame_state (context=<optimized out>, context=<optimized out>, fs=<optimized out>) at ../../../libgcc/unwind-dw2.c:1271
#1  uw_frame_state_for (context=0x30caeb6c, fs=0x30cae914) at ../../../libgcc/unwind-dw2.c:1271
#2  0x30303200 in _Unwind_ForcedUnwind_Phase2 (exc=0x30caf658, context=0x30caeb6c, frames_p=0x30caea90) at ../../../libgcc/unwind.inc:162
#3  0x30303858 in _Unwind_ForcedUnwind (exc=0x30caf658, stop=0x30321dcc <unwind_stop>, stop_argument=0x30caeea4) at ../../../libgcc/unwind.inc:217

#4  0x30321fc0 in __GI___pthread_unwind (buf=<optimized out>) at unwind.c:121
#5  0x30312388 in __do_cancel () at pthreadP.h:313
#6  sigcancel_handler (sig=32, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:162
#7  sigcancel_handler (sig=<optimized out>, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:127

#8  <signal handler called>
#9  0x303266d0 in __futex_abstimed_wait_cancelable64 (futex_word=0x7ffffd78,  expected=1, clockid=<optimized out>, abstime=0x0, private=<optimized out>) at ../sysdeps/nptl/futex-internal.c:66
#10 0x303210f8 in __new_sem_wait_slow64 (sem=0x7ffffd78, abstime=0x0, clockid=0) at sem_waitcommon.c:285
#11 0x00002884 in tf (arg=0x7ffffd78) at throw-pthread-sem.cc:35
```




```
Thread 2 "throw-pthread-s" hit Breakpoint 1, 0x00007ffff7c383b0 in unwind_stop () from /lib64/libpthread.so.0
(gdb) bt
#0  0x00007ffff7c383b0 in unwind_stop () from /lib64/libpthread.so.0
#1  0x00007ffff7c57d29 in _Unwind_ForcedUnwind_Phase2 () from /lib64/libgcc_s.so.1
#2  0x00007ffff7c58442 in _Unwind_ForcedUnwind () from /lib64/libgcc_s.so.1

#3  0x00007ffff7c38546 in __pthread_unwind () from /lib64/libpthread.so.0
#4  0x00007ffff7c2cc99 in sigcancel_handler () from /lib64/libpthread.so.0

#5  <signal handler called>
#6  0x00007ffff7c37a24 in do_futex_wait.constprop () from /lib64/libpthread.so.0
#7  0x00007ffff7c37b28 in __new_sem_wait_slow.constprop.0 () from /lib64/libpthread.so.0
#8  0x000000000040120c in tf (arg=0x7fffffffc900) at throw-pthread-sem.cc:35
#9  0x00007ffff7c2e432 in start_thread () from /lib64/libpthread.so.0
#10 0x00007ffff7b5c9d3 in clone () from /lib64/libc.so.6
```

