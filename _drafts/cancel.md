---
title: Cancel - How pthread_cancel works
layout: post
date: 2020-11-14
---

I have been working on porting GLIBC to the OpenRISC architecture.  This
has taken me quite a bit longer than I anticipated as with GLIBC upstreaming
you need to get every single test to pass.  This was different compared with
GDB and GCC which were a bit more lenient.

For a GLIBC port to go upstream all tests must pass.  This means I need to
learn the in's and out's of each subsystem better than I did before. Including:

 - Thread Unwinding
 - ELF Binary Bootstrapping

I find it a good method to work through each bug by documenting it.  It helps
me track what I have learned, and fixed as well as troubleshoot.

## A Bug

Glibc test `nptl/tst-cancel7` is failing.  The test is a bit tricky as it
uses `system` to startup a copy of itself then cancel's the copy.

It is failing with:

```
error: xpthread_check_return.c:32: pthread_cancel: No such process
```

Basic recon:
 - Try to understand what the test code is doing and where its failing
 - Do an strace to see if it's doing what we expect

Lets look at the code to try to understand it.

tst-cancel7.c

```c
static int
do_test (void)
{
  pthread_t th;
  if (pthread_create (&th, NULL, tf, NULL) != 0)
    {
      puts ("pthread_create failed");
      return 1;
    }

  do
    sleep (1);
  while (access (pidfilename, R_OK) != 0);

  xpthread_cancel (th); // <--- The test fails here
  void *r = xpthread_join (th);

```

The test will start a new thread, (not listed here), that thread will then
spawn another child using the `system()` function.  The test will then wait
a bit and then try to call `xpthread_cancel()` on the child thread.

This is failing.

Looking at the strace:

```
$ strace -t -fo /tmp/tst-cancel.strace /home/shorne/work/gnu-toolchain/build-glibc/nptl/tst-cancel7 --direct

    13996 07:32:06 fcntl64(3, F_SETLK, {l_type=F_WRLCK, l_whence=SEEK_SET, l_start=0, l_len=0}) = 0
    13996 07:32:06 rt_sigsuspend(~[RTMIN RT_1], 8 <unfinished ...>            <-- thread of our child
13994 07:32:08 <... clock_nanosleep_time64 resumed>0x7fb85c14) = 0
13994 07:32:08 faccessat(AT_FDCWD, "/tmp/tst-cancel7-xoLXIn", R_OK) = 0       <-- while (access (pidfilename, R_OK) != 0) returns
13994 07:32:08 write(1, "Cancelling pidfile thread.", 26) = 26                <-- some debugging I added
13994 07:32:08 write(1, "\n", 1)        = 1
13994 07:32:08 openat(AT_FDCWD, "/lib/libgcc_s.so.1", O_RDONLY|O_CLOEXEC) = 3 <-- pthread loading to stack unwinder lib
13994 07:32:08 read(3, "\177ELF\1\2\1\0\0\0\0\0\0\0\0\0\0\3\0\\\0\0\0\1\0\0%\300\0\0\0004"..., 512) = 512
13994 07:32:08 fstat64(3, {st_mode=S_IFREG|0644, st_size=558252, ...}) = 0
13994 07:32:08 mmap2(NULL, 115304, PROT_READ|PROT_EXEC, MAP_PRIVATE|MAP_DENYWRITE, 3, 0) = 0x309d0000
13994 07:32:08 mmap2(0x309ea000, 16384, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_FIXED|MAP_DENYWRITE, 3, 0x18000) = 0x309ea000
13994 07:32:08 mprotect(0x30028000, 7012, PROT_READ|PROT_WRITE) = 0
13994 07:32:08 mprotect(0x30028000, 7012, PROT_READ) = 0
13994 07:32:08 mprotect(0x7fb84000, 8192, PROT_READ|PROT_WRITE|PROT_EXEC|PROT_GROWSDOWN) = 0
13994 07:32:08 mprotect(0x301d0000, 8388608, PROT_READ|PROT_WRITE|PROT_EXEC) = 0
13994 07:32:08 close(3)                 = 0
13994 07:32:08 mprotect(0x309ea000, 8192, PROT_READ) = 0
13994 07:32:08 getpid()                 = 13994
13994 07:32:08 tgkill(13994, 65535, SIGRTMIN) = -1 ESRCH (No such process)   <--- Kill fails with ESRCH
13994 07:32:08 write(1, "error: xpthread_check_return.c:3"..., 35) = 35
13994 07:32:08 write(1, "pthread_cancel: No such process", 31) = 31
```

So interesting, the call to `xpthread_cancel()`, calls `tgkill()` with ids 13994 and 65535.
What is 65535? We should expect 13996.

If we look at pthread_cancel we see:

nptl/pthread_cancel.c

```c
int
__pthread_cancel (pthread_t th)
{
  volatile struct pthread *pd = (volatile struct pthread *) th;

...

          /* The cancellation handler will take care of marking the
             thread as canceled.  */
          pid_t pid = __getpid ();

          int val = INTERNAL_SYSCALL_CALL (tgkill, pid, pd->tid,
                                           SIGCANCEL);
          if (INTERNAL_SYSCALL_ERROR_P (val))
            result = INTERNAL_SYSCALL_ERRNO (val);
...
```

The `pthread_cancel()` implementation gets the pthread structure then
tries to call `tgkill()` with the `pd->tid` argument.

So, why is our `pd->tid` 65535?  Who should be setting it?

The structure is defined here:

nptl/descr.h

```c
/* Thread descriptor data structure.  */
struct pthread
{
  tcbhead_t header;

  /* This descriptor's link on the `stack_used' or `__stack_user' list.  */
  list_t list;

  /* Thread ID - which is also a 'is this thread descriptor (and
     therefore stack) used' flag.  */
  pid_t tid;

  /* Ununsed.  */
  pid_t pid_ununsed;
  ...

```

It should be getting set when we call clone, we see the documentation as.

```
       int clone(int (*fn)(void *), void *stack, int flags, void *arg, ...
                 /* pid_t *parent_tid, void *tls, pid_t *child_tid */ );

               u64 child_tid;    /* Where to store child TID,
                                    in child's memory (pid_t *) */
               u64 parent_tid;   /* Where to store child TID,
                                    in parent's memory (int *) */
```

The child tid is returned via the `child_tid` or `parent_tid` pointer and that is what we see:

sysdeps/unix/sysv/linux/createthread.c

```

  if (__glibc_unlikely (ARCH_CLONE (&start_thread, STACK_VARIABLES_ARGS,
                                    clone_flags, pd, &pd->tid, tp, &pd->tid)
                        == -1))

```

The `ARCH_CLONE` is a wrapper for the clone syscall which is implemented in OpenRISC as:

sysdeps/unix/sysv/linux/or1k/clone.c

```
extern int __or1k_clone (int (*fn)(void *), void *child_stack,
                         int flags, void *arg, pid_t *ptid,
                         void *tls, pid_t *ctid);


/* or1k ABI uses stack for varargs, syscall uses registers.
 * This function moves from varargs to regs. */
int
__clone (int (*fn)(void *), void *child_stack,
         int flags, void *arg, ...
         /* pid_t *ptid, struct user_desc *tls, pid_t *ctid */ )
{
  void *ptid;
  void *tls;
  void *ctid;
  va_list ap;
  int err;

  va_start (ap, arg);
  ptid = va_arg (ap, void *);
  tls = va_arg (ap, void *);
  ctid = va_arg (ap, void *);
  va_end (ap);

  /* Sanity check the arguments */
  err = -EINVAL;
  if (!fn)
    goto syscall_error;
  if (!child_stack)
    goto syscall_error;

                    / *r3           r4     r5   r6    r7   r8   0(r1) */
  return __or1k_clone (fn, child_stack, flags, arg, ptid, tls, ctid);

...
```

and

sysdeps/unix/sysv/linux/or1k/or1k_clone.S

```
ENTRY(__or1k_clone)

        /* To handle GCC varargs we need to use our __clone wrapper to pop
           everything from the stack for us.
           Now everything is placed in the registers which saves us a lot
           of trouble.

           The userland implementation is:

             int clone (int (*fn)(void *), void *child_stack,
                        int flags, void *arg, pid_t *ptid,
                        struct user_desc *tls, pid_t *ctid);
           The kernel entry is:

             int clone (long flags, void *child_stack, int *parent_tid,
                        int *child_tid, struct void *tls)

             NB: tls isn't really an argument, it is read from r7 directly.  */

        /* Put 'fn', 'arg' and 'flags' on the child stack.  */
        l.addi  r4, r4, -12
        l.sw    8(r4), r3
        l.sw    4(r4), r6
        l.sw    0(r4), r5

        l.ori   r3, r5, 0
        /* The child_stack is already in r4.  */
        l.ori   r5, r7, 0 /* parent_tid <- ptid (r7) */
        l.lwz   r6, 0(r1) /* child_tid */
        l.ori   r7, r8, 0 /* tls        <- tls (r8) */

        DO_CALL (clone)
        ...
```

and in the kernel

linux/kernel/fork.c

```
#ifdef CONFIG_CLONE_BACKWARDS
SYSCALL_DEFINE5(clone, unsigned long, clone_flags, unsigned long, newsp,
		 int __user *, parent_tidptr,
		 unsigned long, tls,
		 int __user *, child_tidptr)
#elif defined(CONFIG_CLONE_BACKWARDS2)
SYSCALL_DEFINE5(clone, unsigned long, newsp, unsigned long, clone_flags,
		 int __user *, parent_tidptr,
		 int __user *, child_tidptr,
		 unsigned long, tls)
#elif defined(CONFIG_CLONE_BACKWARDS3)
SYSCALL_DEFINE6(clone, unsigned long, clone_flags, unsigned long, newsp,
		int, stack_size,
		int __user *, parent_tidptr,
		int __user *, child_tidptr,
		unsigned long, tls)
#else
SYSCALL_DEFINE5(clone, unsigned long, clone_flags, unsigned long, newsp,  <-- OpenRISC uses this normal version
		 int __user *, parent_tidptr,
		 int __user *, child_tidptr,
		 unsigned long, tls)
#endif
{
	struct kernel_clone_args args = {
		.flags		= (lower_32_bits(clone_flags) & ~CSIGNAL),
		.pidfd		= parent_tidptr,
		.child_tid	= child_tidptr,
		.parent_tid	= parent_tidptr,
		.exit_signal	= (lower_32_bits(clone_flags) & CSIGNAL),
		.stack		= newsp,
		.tls		= tls,
	};

	return kernel_clone(&args);
}
#endif

```

Now, what did we see in our strace?

```
13994 07:32:04 rt_sigprocmask(SIG_BLOCK, ~[], [], 8) = 0
13994 07:32:04 clone(child_stack=0x309cef14,
                     flags=CLONE_VM|CLONE_FS|CLONE_FILES|CLONE_SIGHAND|CLONE_THREAD|CLONE_SYSVSEM|CLONE_SETTLS|CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID,
                     parent_tid=[13995],
                     tls=0x309cf930,
                     child_tidptr=0x309cf488) = 13995
13994 07:32:04 rt_sigprocmask(SIG_SETMASK, [], NULL, 8) = 0
13994 07:32:04 clock_nanosleep_time64(CLOCK_REALTIME, 0, {tv_sec=4, tv_nsec=0},  <unfinished ...>
  13995 07:32:04 set_robust_list(0x309cf490, 12) = 0
  13995 07:32:04 rt_sigprocmask(SIG_SETMASK, [], NULL, 8) = 0

```

That looks wrong, `parent_tid` is set correctly, but child_tidptr is showing a strange address.  
However, debugging this it appears all correct.

This below stack trace is taken right after the syscall to clone completes, we see:

  1. There are 2 threads created with the new one being PID `14005`
  2. The contents of arg `parent_tidptr` contain `14005`
  3. The contents of arg `child_tidptr` contain `14005` (and its the same address as `parent_tidptr` as expected)
  4. The contents of `tls` look ok.

```
(gdb) info threads
  Id   Target Id                                   Frame
* 1    Thread 0x301c8010 (LWP 14000) "tst-cancel7" 0x30154320 in __or1k_clone () from /lib/libc.so.6
  2    Thread 0x309cf420 (LWP 14005) "tst-cancel7" 0x30154320 in __or1k_clone () from /lib/libc.so.6
(gdb) bt
#0  0x30154320 in __or1k_clone () from /lib/libc.so.6
#1  0x30154508 in __GI___clone (fn=<optimized out>, child_stack=<optimized out>, flags=<optimized out>, arg=<optimized out>) at ../sysdeps/unix/sysv/linux/or1k/clone.c:52
#2  0x30030c88 in create_thread (pd=0x309cf420, attr=0x7ffffb74, stopped_start=0x7ffffb72, stackaddr=<optimized out>, thread_ran=0x7ffffb73)
    at ../sysdeps/unix/sysv/linux/createthread.c:103
#3  0x300330b0 in __pthread_create_2_1 (newthread=newthread@entry=0x7ffffc68, attr=attr@entry=0x0, start_routine=start_routine@entry=0x3cb0 <tf>, arg=arg@entry=0x0)
    at pthread_create.c:811
#4  0x00003874 in do_test () at tst-cancel7.c:105
#5  0x000047b8 in run_test_function (argc=argc@entry=1, argv=argv@entry=0x7ffffdf8, config=config@entry=0x7ffffd38) at support_test_main.c:231
#6  0x00004f18 in support_test_main (argc=1, argc@entry=2, argv=0x7ffffdf8, argv@entry=0x7ffffdf4, config=config@entry=0x7ffffd38) at support_test_main.c:402
#7  0x00003e5c in main (argc=2, argv=0x7ffffdf4) at ../support/test-driver.c:168
(gdb) x/2d $r5
0x309cf488:     0       14005
(gdb) x/2d $r6
0x309cf488:     0       14005
(gdb) x/1w $r7
0x309cf930:     0x301c4a0c
```

Now, let's debug a bit more, when does pd->tid go bad? (4 days later) Let's use a watchpoint

```
Thread 2 "tst-cancel7" hit Breakpoint 2, __GI___clone (fn=0x3013c9c8 <__spawni_child>, child_stack=0x309da000, flags=16657, arg=0x309ce8c0)
    at ../sysdeps/unix/sysv/linux/or1k/clone.c:39
39        va_start (ap, arg);
(gdb) p (*(struct pthread *)th).tid
$2 = 14232
(gdb) watch (*(struct pthread *)th).tid
Watchpoint 3: (*(struct pthread *)th).tid
(gdb) c
Continuing.
[Detaching after vfork from child process 14233]

Thread 2 "tst-cancel7" hit Watchpoint 3: (*(struct pthread *)th).tid

Old value = 14232
New value = 65535
0x30154320 in __or1k_clone () from /lib/libc.so.6
```

This shows that the call to clone in system caused the value to be set to -1.  So is this
the kernel clone setting the value to -1?  Or is it the child process?
Because gdb detached from the child process we cannot tell what it is doing

To enable looking into the child we need to `set follow-fork-mode child` in gdb.

This did not reveal anything.  We are not sure if our value is getting set
by the kernel or userspace.

I had to think about this one for a while.

## Creating a QEMU plugin

At first I thought this would be a good time to implement hardware watchpoints
into the linux kernel.  However, after some inspection of the `mor1kx`
CPU source code, I realized hardware watchpoints have not been implemented
there yet.

Next I considered if we could capture this while running in QEMU.

I couldn't implement hardware watchpoints do I did a qemu plugin,
to report when the stores to the tid happen.

I ran our test in QEMU with the tracewatch plugin and could see this:

```
c000c88c: ST 309f7478 <- KERNEL initial store to set tid
30176290: ST 309f7478 <- USER   bad boy setting it to ffff
c0009ba4: ST 309f7478 <- KERNEL final store to set back to 0
```

BOOM, we can see the instruction address that is writing to our memory location.

to figure out what this code is we have to look at objdumps.,

With a bit of knowledge we know that c0* is kernel, and 30* is user space.

But lets check to ensure that is as expected.

So if we look at the kernel:

First one is in clone. OK
```
c000c884:       13 ff ff d5     l.bf c000c7d8 <kernel_clone+0xb8>
c000c888:       1a 20 00 00     l.movhi r17,0x0
c000c88c:       d4 13 58 00     l.sw 0(r19),r11
c000c890:       1a 20 00 00     l.movhi r17,0x0
```

Third one is in mm_release. OK as expected:

```
c0009b9c:       10 00 00 04     l.bf c0009bac <mm_release+0xbc>
c0009ba0:       15 00 00 00     l.nop 0x0
c0009ba4:       d4 03 88 00     l.sw 0(r3),r17
c0009ba8:       a8 a0 00 01     l.ori r5,r0,0x1
c0009bac:       d4 01 00 00     l.sw 0(r1),r0
```

So the second one is definitely in user space somewhere, so which binary is it?

When the test runs we can look at /proc/$pid/maps to find out.  We can see it is
inside of libc.so.


```
/ # cat /proc/111/maps 
00002000-00008000 r-xp 00000000 00:0000000d 398523  /home/shorne/work/gnu-toolchain/build-glibc/nptl/tst-cancel7
00008000-0000a000 r--p 00004000 00:0000000d 398523  /home/shorne/work/gnu-toolchain/build-glibc/nptl/tst-cancel7
0000a000-0000c000 rw-p 00006000 00:0000000d 398523  /home/shorne/work/gnu-toolchain/build-glibc/nptl/tst-cancel7
30000000-3002a000 r-xp 00000000 00:0000000d 403362  /home/shorne/work/gnu-toolchain/build-glibc/elf/ld.so
3002a000-3002c000 r--p 00028000 00:0000000d 403362  /home/shorne/work/gnu-toolchain/build-glibc/elf/ld.so
3002c000-3002e000 rw-p 0002a000 00:0000000d 403362  /home/shorne/work/gnu-toolchain/build-glibc/elf/ld.so
3002e000-3004e000 r-xp 00000000 00:0000000d 396850  /home/shorne/work/gnu-toolchain/build-glibc/nptl/libpthread.so
3004e000-30050000 r--p 0001e000 00:0000000d 396850  /home/shorne/work/gnu-toolchain/build-glibc/nptl/libpthread.so
30050000-30052000 rw-p 00020000 00:0000000d 396850  /home/shorne/work/gnu-toolchain/build-glibc/nptl/libpthread.so
30052000-30054000 rw-p 00000000 00:00 0 
30054000-301e8000 r-xp 00000000 00:0000000d 300640  /home/shorne/work/gnu-toolchain/build-glibc/libc.so
301e8000-301ea000 ---p 00194000 00:0000000d 300640  /home/shorne/work/gnu-toolchain/build-glibc/libc.so
301ea000-301ec000 r--p 00194000 00:0000000d 300640  /home/shorne/work/gnu-toolchain/build-glibc/libc.so
301ec000-301ee000 rw-p 00196000 00:0000000d 300640  /home/shorne/work/gnu-toolchain/build-glibc/libc.so
301ee000-301f4000 rw-p 00000000 00:00 0 
301f4000-301f6000 rw-s 00000000 00:00000001 7    /dev/zero (deleted)
301f6000-301f8000 ---p 00000000 00:00 0 
301f8000-30af8000 rw-p 00000000 00:00 0 
7f8f8000-7f91a000 rw-p 00000000 00:00 0          [stack]
```


With a bit of math we now have the *Instruction Virtual Address* and the *Library
Load Address*.  If we subtract them we can get the offset of the Instruction in
library.

`0x30176290 - 0x30054000 = 122290`

That brings us to `__or1k_clone`?

```__or1k_clone
  122280:       9d 60 00 ac     l.addi r11,r0,172
  122284:       20 00 00 01     l.sys 0x1
  122288:       15 00 00 00     l.nop 0x0
  12228c:       9c 6a fa d0     l.addi r3,r10,-1328
  122290:       d4 03 58 78     l.sw 120(r3),r11
  122294:       85 61 00 08     l.lwz r11,8(r1)
  122298:       48 00 58 00     l.jalr r11
```

There is code in `__or1k_clone` assembly code to set the TID to -1 if we are
doing CLONE_VM.  I am not sure where this code came from and no other ports
do anything of the sort.  Removing this fixes the issue.

## Further Reading
 - https://www.gnu.org/software/hurd/glibc/startup.html
 - https://refspecs.linuxbase.org/LSB_3.1.0/LSB-generic/LSB-generic/baselib---libc-start-main-.html
