---
title: Start me Up - How ELF binaries get to main
layout: post
date: 2020-11-14
---

I have been working on porting GLIBC to the OpenRISC architecture.  This
has taken me quite a bit longer than I anticipated as with GLIBC upstreaming
you need to get every single test to pass.  This was different compared with
GDB and GCC which were a bit more lenient.

See Unwinding!

## A Bug

Glibc tests `nptl/tst-fini1` is failing.  The test is pretty simple, consisting of 2 files.

tst-finit.c

```
#include <unistd.h>

extern void m (void);

int main (void)
{
  alarm (5);
  m ();
  return 42;
}
```

mod.c

```
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static void *
tf (void *arg)
{
  int fds[2];
  if (pipe (fds) != 0)
    {
      puts ("pipe failed");
      exit (1);
    }

  char buf[10];
  read (fds[0], buf, sizeof (buf));

  puts ("read returned");
  exit (1);
}

static pthread_t th;

static void
__attribute ((destructor))
dest (void)
{
  if (pthread_cancel (th) != 0)
    {
      puts ("cancel failed");
      _exit (1);
    }
  void *r;
  if (pthread_join (th, &r) != 0)
    {
      puts ("join failed");
      _exit (1);
    }
  /* Exit successfully.  */
  _exit (0);
}

void
m (void)
{
  if (pthread_create (&th, NULL, tf, NULL) != 0)
    {
      puts ("create failed");
      _exit (1);
    }
}
```


The m function, creates a new thread `tf` which creates a pipe and reads from it.

There is a destructor registers, which will simply cancel `th` and exit 0.  Our
program should therefore exit 0.

Instead our program is exiting with 42.  This means the destructor is failing to
run.

## How are destructors run?

Destructors in modern runtimes are registered in the init fini arrays.

You can see in our binary as:

The `dest` function is at address `0x6b4`

```
or1k-elf-readelf -s ../build-glibc/nptl/tst-fini1mod.so

...
    38: 00000000     0 FILE    LOCAL  DEFAULT  ABS tst-fini1mod.c
    39: 0000062c   136 FUNC    LOCAL  DEFAULT   10 tf
    40: 000006b4   172 FUNC    LOCAL  DEFAULT   10 dest
    41: 00004044     4 OBJECT  LOCAL  DEFAULT   19 th
    42: 00000000     0 FILE    LOCAL  DEFAULT  ABS crtstuff.c
...
```

We have init fini arrays of size 4 and 8.

```
or1k-elf-readelf -S ../build-glibc/nptl/tst-fini1mod.so 
There are 31 section headers, starting at offset 0x3b54:

Section Headers:
  [Nr] Name              Type            Addr     Off    Size   ES Flg Lk Inf Al
  [ 0]                   NULL            00000000 000000 000000 00      0   0  0
  [ 1] .note.ABI-tag     NOTE            000000f4 0000f4 000020 00   A  0   0  4
  [ 2] .gnu.hash         GNU_HASH        00000114 000114 000020 04   A  3   0  4
...
  [12] .eh_frame         PROGBITS        00000828 000828 000004 00   A  0   0  4
  [13] .hash             HASH            0000082c 00082c 00005c 04   A  3   0  4
  [14] .init_array       INIT_ARRAY      00003f14 001f14 000004 04  WA  0   0  4
  [15] .fini_array       FINI_ARRAY      00003f18 001f18 000008 04  WA  0   0  4
...
```


If we look at the content of the array we can see the `.fini_array` contains our
`dest` function.  So this is setup correctly.

The other bits are `frame_dummy` in `.init_array` and `__do_global_dtors_aux`
in `.fini_array`.

```
or1k-elf-readelf -x .init_array -x .fini_array ../build-glibc/nptl/tst-fini1mod.so

Hex dump of section '.init_array':
  0x00003f14 00000624                            ...$


Hex dump of section '.fini_array':
  0x00003f18 000005a8 000006b4                   ........
```

Now, we see the `.fini_array` is setup correctly.  But who is in charge of iterating
and executing these?  Also, this is the `.fini_array` of a dynamically linked shared
object.  How does that get loaded?

Lets look at how it all comes together when a binary is boot strapped.

## Boot Sequences

The players

sysdeps/or1k/dl-start.S - in /lib/ld-or1k-linux.so.1

```
 _start                      - os jumps to here
   f = _dl_start ();
   _dl_start_user
     r3 _rtld_local  (aka main_map)
     r4 _dl_argc
     r5 _dl_argv
     _dl_init ();

     r3 _dl_fini
     f ();                   - goes to sysdeps/or1k/start.S
```

elf/rtld.c - in /lib/ld

```
  _dl_start (void *arg)
   /* lots of bootstrap stuff, tls, dl_rtld_map, dl_main etc */
   entry = _dl_start_final (arg)

   return entry;
```

elf/dl-init.c - in lib/ld-

```
  _dl_init (struct link_map *main_map, int argc, char **argv, char **env)
    /* call initfinit init functions */
    preinit()
    while (i-- > 0)
      call_init (main_map->l_initfini[i], argc, argv, env);
```

sysdeps/or1k/start.S - in our executable

```
 ENTRY_POINT                 - dl_start resolves this
   r3 main
   r4                        \__ not touched from sysdeps/or1k/dl-start.S
   r5                        /
   r6 __libc_csu_init
   r7 __libc_csu_fini
   r8 - r3 (rtld_fini)

   __libc_start_main ();
```

csu/libc-start.c

```
                    r3    r4    r5    r6    r7    r8
 __libc_start_main (main, argc, argv, init, fini, rtld_fini)
   __cxa_atexit (rtld_fini)
   __cxa_atexit (fini)
   init ();
   main ();
```

## Back to fini

From above we can see that _dl_fini is registered to be called during exit.  What is _dl_fini?

elf/dl-fini.c

```
void
_dl_fini (void)
{
  /* Lots of fun ahead.  We have to call the destructors for all still
     loaded objects, in all namespaces.  The problem is that the ELF
     specification now demands that dependencies between the modules
     are taken into account.  I.e., the destructor for a module is
     called before the ones for any of its dependencies.
...
```

It looks like this is the correct spot.  When debugging I see that this bit of code is
never executed.  DEbugging `__libc_start_main` we can see infact it `0x0`.

```
(gdb) bt
#0  __libc_start_main (main=0x2478 <main>, argc=1, argv=0x7ffffe04,
    init=0x24a8 <__libc_csu_init>, fini=0x2574 <__libc_csu_fini>,
    rtld_fini=0x0, stack_end=0x7ffffe00) at libc-start.c:143
```

Looking at the start sequence we see:

```
00002354 <_start>:
    2354:       84 81 00 00     l.lwz r4,0(r1)
    2358:       9c a1 00 04     l.addi r5,r1,4
    235c:       18 60 00 00     l.movhi r3,0x0
    2360:       a8 63 24 78     l.ori r3,r3,0x2478
    2364:       18 c0 00 00     l.movhi r6,0x0
    2368:       a8 c6 24 a8     l.ori r6,r6,0x24a8
    236c:       18 e0 00 00     l.movhi r7,0x0
    2370:       a8 e7 25 74     l.ori r7,r7,0x2574
    2374:       19 00 00 00     l.movhi r8,0x0     <-- hard code to 0x0
    2378:       d7 e1 0f fc     l.sw -4(r1),r1
    237c:       9c 21 ff fc     l.addi r1,r1,-4
    2380:       18 40 00 00     l.movhi r2,0x0
    2384:       19 20 00 00     l.movhi r9,0x0
    2388:       03 ff ff e7     l.j 2324 <.plt+0x10>
    238c:       15 00 00 00     l.nop 0x0
```

This means we are running the non PIC version of the _start code.

Fixing to always initialize `r8` fixes the `tst-fini1` test with this patch:

```
diff --git a/sysdeps/or1k/start.S b/sysdeps/or1k/start.S
index 154ca0c7ed..55ec46cef4 100644
--- a/sysdeps/or1k/start.S
+++ b/sysdeps/or1k/start.S
@@ -44,6 +44,9 @@ ENTRY (ENTRY_POINT)
        l.lwz   r4, 0(r1)
        l.addi  r5, r1, 4
 
+       /* Pass in rtld_fini from dl-start.S.  */
+       l.or    r8, r3, r3
+
 #ifdef PIC
        /* Obtain a pointer to .got in r16 */
        l.jal   0x8
@@ -51,9 +54,6 @@ ENTRY (ENTRY_POINT)
        l.ori   r16, r16, gotpclo(_GLOBAL_OFFSET_TABLE_+0)
        l.add   r16, r16, r9
 
-       /* Pass in rtld_fini from dl-start.S.  */
-       l.or    r8, r3, r3
-
        /* Pass in the the main symbol.  */
        l.lwz   r3, got(main)(r16)
 
@@ -70,9 +70,6 @@ ENTRY (ENTRY_POINT)
        l.ori   r6, r6, lo(__libc_csu_init)
        l.movhi r7, hi(__libc_csu_fini)
        l.ori   r7, r7, lo(__libc_csu_fini)
-
-       /* Pass in rtld_fini as NULL as there is none in static builds.  */
-       l.movhi r8, 0x0
 #endif
        /* Push stack limit onto the stack.
           This provides the highest stack address to user code (as stack grows
```

However, this may break statically linked tests.  We will see.

## Dynamically Linked
I am talking about dynamically linked ELF binaries here.  Static binaries are similar but, there
is a different `_dl_start` routine.


## Further Reading
 - https://www.gnu.org/software/hurd/glibc/startup.html
 - https://refspecs.linuxbase.org/LSB_3.1.0/LSB-generic/LSB-generic/baselib---libc-start-main-.html
