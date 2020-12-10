---
title: Unwinding - How the C++ Exceptions Work
layout: post
date: 2020-11-01
---

I have been working on porting GLIBC to the OpenRISC architecture.  This has
taken longer than I expected as with GLIBC upstreaming you we must get every
single test to pass.  This was different compared with GDB and GCC which were a
bit more lenient.

My [first upstreaming attempt](https://lists.librecores.org/pipermail/openrisc/2020-May/002602.html)
was completely tested on the [QEMU](https://www.qemu.org) simulator.  I have
since added an FPGA [LiteX SoC](https://github.com/enjoy-digital/litex/blob/master/README.md)
to my test platform options.  LiteX runs Linux on the OpenRISC mor1kx softcore
and tests are loaded over an SSH session.  The SoC eliminates an issue I was
seeing on the simulator where under heavy load it appears the [MMU starves the kernel](https://github.com/openrisc/linux/issues/12)
from getting any work done.

To get to where I am now this required:

- Fixing buggy [LiteETH network driver](https://github.com/litex-hub/linux/commit/78969c54328e35b360d9452c7602f21107a13d22)
  in LiteX.
- Updating the OpenRISC GDB port to [support gdbserver](https://github.com/stffrdhrn/binutils-gdb/commit/9d0d2e9bef5c84caa7f05cc7ddba1e092e2b5120)
  and [native debugging](https://github.com/stffrdhrn/binutils-gdb/commit/82e99d5df56be3b18c63e613d00e2367fb5a78b7)
- Fixing bugs in the [Linux Kernel](https://github.com/stffrdhrn/linux/commit/28b852b1dc351efc6525234c5adfd5bc2ad6d6e1) and
  [GLIBC](https://github.com/stffrdhrn/or1k-glibc/commit/75ddf155968299042e4d2b492e3b547c86d4672e) to get gdbserver and native suport working

Adding GDB Linux debugging support is great because it allows debugging of
multithreaded processes and signal handling; which we are going to need.

## A Bug

Our story starts when I was trying to fix is a failing GLIBC [NPTL](https://en.wikipedia.org/wiki/Native_POSIX_Thread_Library)
test case.  The test case involves C++ exceptions and POSIX threads.
The issue is that the `catch` block of a `try/catch` block is not
being called.  Where do we even start?

My plan for approaching test case failures is:
1. Understand what the test case is trying to test and where its failing
2. Create a hypothesis about where the problem is
3. Understand how the failing API's works internally
4. Debug until we find the issue
5. If we get stuck go back to `2.`

Let's have a try.

### Understanding the Test case

The GLIBC test case is [nptl/tst-cancel24.cc](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/tst-cancel24.cc;h=1af709a8cab1d422ef4401e2b2d178df86f863c5;hb=HEAD)`.
The test starts in the `do_test` function and it will create a child thread with `pthread_create`.
The child thread executes function `tf` which waits on a semaphore until the parent thread cancels it.  It
is expected that the child thread, when cancelled , will call it's catch blocks.

The failure is that the `catch` block is not getting run as evidenced by the `except_caught` variable
not being set to `true`.

Below is an excerpt from the test showing the `tf` function.

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

So the `catch` block is not being run.  Simple, but where do we start to
debug that?  Let's move onto the next step.

### Creating a Hypothesis

This one is a bit tricky as it seems C++ `try/catch` blocks are broken. Here, I am
working on GLIBC testing, what does that have to do with C++?

To get a good idea of where the problem is, I tried to modify the test to test
some simple ideas. First, maybe there is a problem with catching exceptions
throws from thread child functions.

```c
static void do_throw() { throw 99; }

static void * tf () {
  try {
      monitor m;
      while (1) do_throw();
  } catch (...) {
      except_caught = true;
  }
  return NULL;
}
```

No, this works correctly.  So `try/catch` is working.

**Hypothesis**: There is a problem handling exceptions while in a syscall.
There may be something broken with OpenRISC related to how we setup stack
frames for syscalls that makes the unwinder fail.

How does that work?  Let's move onto the next step.

### Understanding the Internals

For this case we need to understand how C++ exceptions work.  Also, we need to know
what happens when a thread is cancelled in a multithreaded
([pthread](https://en.wikipedia.org/wiki/POSIX_Threads)) glibc environment.

C++ Exceptions work using GCC stack frame unwinding infrastructure.  There are a
few contributors to C++ exceptions and unwinding which are:

- **DWARF** - provided by our program and libraries in the `.eh_frame` ELF
  section
- **GLIBC** - provides the pthread runtime and cleanup callbacks to the GCC unwinder code
- **GCC** - provides libraries for dealing with exceptions
-- `libgcc_s.so` - handles unwinding by reading program **DWARF** metadata and doing the frame decoding
-- `libstdc++.so.6` - provides the C++ personality routine which
   identifies and prepares `catch` blocks for execution

#### DWARF
ELF binaries provide debugging information in a data format called
[DWARF](https://en.wikipedia.org/wiki/DWARF).  The name was chosen to maintain a
fantasy theme.  Lately the Linux community has a new debug format called
[ORC](https://lwn.net/Articles/728339/).

Though this is a debugging format and usually stored in `.debug_frame`,
`.debug_info`, etc sections, a stripped down version it is used for exception
handling.

Each binary file that support unwinding contains the `.eh_frame` section to
provide unwinding information.  This can be seen with the `readelf` program.

```
$ readelf -S sysroot/lib/libc.so.6
There are 70 section headers, starting at offset 0xaa00b8:

Section Headers:
  [Nr] Name              Type            Addr     Off    Size   ES Flg Lk Inf Al
  [ 0]                   NULL            00000000 000000 000000 00      0   0  0
  [ 1] .note.ABI-tag     NOTE            00000174 000174 000020 00   A  0   0  4
  [ 2] .gnu.hash         GNU_HASH        00000194 000194 00380c 04   A  3   0  4
  [ 3] .dynsym           DYNSYM          000039a0 0039a0 008280 10   A  4  15  4
  [ 4] .dynstr           STRTAB          0000bc20 00bc20 0054d4 00   A  0   0  1
  [ 5] .gnu.version      VERSYM          000110f4 0110f4 001050 02   A  3   0  2
  [ 6] .gnu.version_d    VERDEF          00012144 012144 000080 00   A  4   4  4
  [ 7] .gnu.version_r    VERNEED         000121c4 0121c4 000030 00   A  4   1  4
  [ 8] .rela.dyn         RELA            000121f4 0121f4 00378c 0c   A  3   0  4
  [ 9] .rela.plt         RELA            00015980 015980 000090 0c  AI  3  28  4
  [10] .plt              PROGBITS        00015a10 015a10 0000d0 04  AX  0   0  4
  [11] .text             PROGBITS        00015ae0 015ae0 155b78 00  AX  0   0  4
  [12] __libc_freeres_fn PROGBITS        0016b658 16b658 001980 00  AX  0   0  4
  [13] .rodata           PROGBITS        0016cfd8 16cfd8 0192b4 00   A  0   0  4
  [14] .interp           PROGBITS        0018628c 18628c 000018 00   A  0   0  1
  [15] .eh_frame_hdr     PROGBITS        001862a4 1862a4 001a44 00   A  0   0  4
  [16] .eh_frame         PROGBITS        00187ce8 187ce8 007cf4 00   A  0   0  4
  [17] .gcc_except_table PROGBITS        0018f9dc 18f9dc 000341 00   A  0   0  1
...
```

We can decode the metadata using `readelf` as well using the
`--debug-dump=frames-interp` and `--debug-dump=frames` arguments.

The `frames` dump provides a raw output of the DWARF metadata for each frame.
This is not usually very useful, but it shows how DWARF metadata is actually
a bytecode which the interpreter needs to execute to understand how to derive
the values of registers based current PC.

There is an interesting talk about [Exploiting the hard-working
DWARF](https://www.cs.dartmouth.edu/~sergey/battleaxe/hackito_2011_oakley_bratus.pdf).

```
$ readelf --debug-dump=frames sysroot/lib/libc.so.6

...
00016788 0000000c ffffffff CIE
  Version:               1
  Augmentation:          ""
  Code alignment factor: 4
  Data alignment factor: -4
  Return address column: 9

  DW_CFA_def_cfa_register: r1
  DW_CFA_nop

00016798 00000028 00016788 FDE cie=00016788 pc=0016b584..0016b658
  DW_CFA_advance_loc: 4 to 0016b588
  DW_CFA_def_cfa_offset: 4
  DW_CFA_advance_loc: 8 to 0016b590
  DW_CFA_offset: r9 at cfa-4
  DW_CFA_advance_loc: 68 to 0016b5d4
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 56 to 0016b60c
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 36 to 0016b630
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 40 to 0016b658
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
```

The `frames-interp` argument is a bit more clear.  We can see
two things:
  - `CIE` - Common Information Entry
  - `FDE` - Frame Description Entry

The `CIE` provides starting point information for each child `FDE` entry.  Some
things to point out we see, `ra=9` indicates the return address is stored in
register `r9`.  Also we see CFA `r1+0` indicates the frame pointer is stored in
register `r1`.  We can also see the stack frame size is `4` bytes.

```
$ readelf --debug-dump=frames-interp sysroot/lib/libc.so.6

00016788 0000000c ffffffff CIE "" cf=4 df=-4 ra=9
   LOC   CFA
00000000 r1+0

00016798 00000028 00016788 FDE cie=00016788 pc=0016b584..0016b658
   LOC   CFA      ra
0016b584 r1+0     u
0016b588 r1+4     u
0016b590 r1+4     c-4
0016b5d4 r1+4     c-4
0016b60c r1+4     c-4
0016b630 r1+4     c-4
0016b658 r1+0     u
```

#### GLIBC

GLIBC provides `pthreads` which when used with C++ needs to support exception
handling.  The main place exceptions are used with `pthreads` is when cancelling
a thread.  A cancel signal is sent to the thread which causes an exception, remember
[cancel exceptions must be rethrown.](https://udrepper.livejournal.com/21541.html)

This is implemented with the below APIs.

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
```

![Pthread Signalled](/content/2020/pthread-signalled-seq.png)

![Pthread Normal](/content/2020/pthread-normal-seq.png)

#### GCC

GCC provides the exception handling and unwinding cababilities
to the c++ runtime.  They are implemented by:

   - In file [libgcc/unwind.inc](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;hb=HEAD)
     - **DWARF** implementations [libgcc/unwind-dw2.c](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;hb=HEAD)
     - **FDE** lookup code [libgcc.unwind-dw2-fce.c](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2-fde.c;hb=HEAD)

The IA-64 C++ Abis

*Forced Unwinds*

   - `_Unwind_ForcedUnwind` - calls:
     - uw_init_context()           - load details of the current frame, from cpu/stack.
     - _Unwind_ForcedUnwind_Phase2 - do the frame iterations
     - uw_install_context()        - actually context switch to the final frame.
   - `_Unwind_ForcedUnwind_Phase2` - loops forever doing:
     - uw_frame_state_for() - setup FS for the current context (one frame up)
     - stop(1, action, exc, context, stop_argument)
     - fs.personality (1, exc, context) - called with `_UA_FORCE_UNWIND | _UA_CLEANUP_PHASE`
     - uw_advance_context () - move current context one frame up

*Raising Exceptions*

Exceptions raise programatically run very similar to the forced exception, but
there is no `stop` function and exception unwinding is 2 phase.

   - `_Unwind_RaiseException` - similar to `_Unwind_ForcedUnwind`, but no `stop`, calls:
     - `uw_init_context()`         - loaded details of the current frame, from cpu/stack.
     - Do phase 1 loop:
       - `uw_frame_state_for()`    - populate FS from current context + DWARF
       - fs.personality            - called with `_UA_SEARCH_PHASE`
       - `uw_update_context()`     - pupulated CONTEXT from FS
     - `_Unwind_RaiseException_Phase2` - do the frame iterations
     - `uw_install_context()`      - Exit unwinder jumping to selected frame
   - `_Unwind_RaiseException_Phase2` - Do phase 2 - loops forever doing:
     - `uw_frame_state_for()`  - Populate FS from CONTEXT + DWARF
     - fs.personality          - called with `_UA_CLEANUP_PHASE`
     - uw_update_context()      - pupulated CONTEXT from FS

*Implementations inside of GCC*

   - DWARF, the followig methods are implemented using the dwarf (there are other implementations
     in GCC, we and most architectures now use DWRF).
     - uw_init_context FDE frame description entry
     - uw_install_context
     - uw_frame_state_for
     - uw_advance_context

*Personality*

  - The personality routine is the interface between the unwind routines and the
    c++ (or other language) runtime, which handles the exception handling
    logic for that language.

  - C++
    [__gxx_personality_v0](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libstdc%2B%2B-v3/libsupc%2B%2B/eh_personality.cc;h=fd7cd6fc79886bf17aea6bc713d2a3840aa31326;hb=HEAD#l336)

    In C++ the personality routine is executed for each stack frame.  The
    function checks if there is a catch block that matches the exception being
    thrown.  If there is a match, it will update the context to prepare it to jump
    into the catch routine and return `_URC_INSTALL_CONTEXT`.
    If there is no catch block matching it returns `_URC_CONTINUE_UNWIND`.

    In the case of `_URC_INSTALL_CONTEXT` then the `_Unwind_ForcedUnwind_Phase2`
    loop breaks and calls `uw_install_context`.

#### Unwinding through a Signal Frame

A process must be context switched to kernel space in order to receive a signal.

The signal handler is executed in user space in a stack frame setup by the kernel.
The signal frame.

Signal handlers may throw exceptions too, this means the unwinder needs to know
about the signal frames.

For OpenRISC linux this is handled in libgcc in [linux-unwind.h](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/config/or1k/linux-unwind.h;h=c7ed043d3a89f2db205fd78fcb5db21f6fb561b2;hb=HEAD)

```c
static _Unwind_Reason_Code
or1k_fallback_frame_state (struct _Unwind_Context *context,
			   _Unwind_FrameState *fs)
```

The `or1k_fallback_frame_state` checks if the current frame is a signal frame
by confirming the return address is a **Trampoline**.  If it is a trampoline
it looks into the kernel saved `pt_regs` to find the previous user frame.

![The Stack Frame after an Interrupt](/content/2020/stack-frame-int.png)

![The Stack Frame in a Sig Handler](/content/2020/stack-frame-in-handler.png)

After the sighandler action code returns we return and run `r9`, which calls sigreturn.

  - restore blocked flags (from before syscall)
  - restore mcontext
  - restore altstack
    - return r11


**Trampoline Code**

The unwinder detects that the stack frame is a *Signal Frame* by checking the
code pointed to by the return address register `r9`.  If we find the trampoline
code (which is always the same), the unwinder code will unwind the context
back to the previos user frame by inwpecting the saved mcontext registers.

```
	l.ori r11,r0,__NR_sigreturn
	l.sys 1
	l.nop
```

### Debugging the Issue

With GDB we can trace right to the start of the exception handling logic by setting
our breakout at `_Unwind_ForcedUnwind`.  This is right before we start unwinding stacks.

Stack unwinding is started with one of 2 functions:
 - `_Unwind_ForcedUnwind` - called when our thread gets a stopping signal
 - `_Unwind_RaiseException` - called when we want to raise an exception

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

Debugging when we unwind a signal frame can be seen when we place
a breakpoint on `or1k_fallback_frame_state`.

As we can see the stack trace goes through the signal frame and to
the original thread. It works correctly.

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

Debugging when the unwinding stops can be done by setting a breakpoint
on the `unwind_stop` function.

When debugging I was able to see that the unwinder failed when looking for
the `__futex_abstimed_wait_cancelable64` frame.

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

### A second Hypothosis

Debugging showed that the uwinder is working correctly, and it can properly unwind through
our signal frames.  However, the unwinder is bailing out early before it gets to the `tf`
frame which has the catch block we need to execute.

We need another idea.

I noticed that the unwinder failed to find the **DWARF** info for `__futex_abstimed_wait_cancelable64`.

Looking at `libpthread.so` this function was missing completely from the `.eh_frame`
metadata.

Who create the `.eh_frame` anyway?  GCC or Binutils (Assembler). If we run GCC
with the `-S` argument we can see GCC will output `.cfi` directives.  These
`.cfi` annotations are what gets compiled to the to `.eh_frame`, an example:


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

When looking at the glibc build I noticed the `.eh_frame` data for
`__futex_abstimed_wait_cancelable64` is missing from futex-internal.o. The one
where unwinding is failing we find it was completely mising `.cfi` directives.
This means something is wrong with GCC


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
 - [Reliable DWARF Unwinding](https://fzn.fr/projects/frdwarf/dwarf-oopsla19-slides.pdf)
 - [Exception Frames](https://refspecs.linuxfoundation.org/LSB_3.0.0/LSB-PDA/LSB-PDA/ehframechpt.html)


