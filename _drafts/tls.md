---
title: Thread Local Storage
layout: post
date: 2019-11-29 06:47
categories: [ hardware, embedded, openrisc ]
---

*This is an ongoing series of posts on ELF Binary Relocations and Thread
Local Storage.  This article covers only Thread Local Storage and assumes
the reader has had a primer in ELF Relocations, if not please start with
my previous article [ELF Binaries and Relocation Entries]({% post_url
2019-11-29-relocs %})*

In the last article we covered ELF Binary internals and how relocation entries
are used to during link time to allow our programs to access symbols
(variables).  However, what if we want a different variable instance for each
thread?  This is where thread local storage (TLS) comes in.

In this article we will discuss how TLS works.  Our outline:

 - [TLS Sections](#tls-sections)
 - [TLS data structures](#tls-data-structures)
 - [TLS access models](#tls-access-models)
   - [Global Dynamic](#global-dynamic)
   - [Local Dynamic](#local-dynamic)
   - [Initial Exec](#initial-exec)
   - [Local Exec](#local-exec)
 - [Relocation Relaxation](#relocation-relaxation)

As before, the examples in this article can be found in my [tls-examples](https://github.com/stffrdhrn/tls-examples)
project.  Please check it out.

## Thread Local Storage

Did you know that in C you can prefix variables with `__thread` to create
[thread local](https://gcc.gnu.org/onlinedocs/gcc/Thread-Local.html) variables?

### Example

```
__thread int i;
```

A thread local variable is a variable that will have a unique instance per thread.
Each time a new thread is created, the space required to store the thread local
variables is allocated.

TLS variables are stored in dynamic TLS sections.

## TLS Sections

In the previous article we saw how variables were stored in the `.data` and
`.bss` sections.  These are initialized once per program or library. 

When we get to binaries that use TLS we will additionally have `.tdata` and
`.tbss` sections.

 - `.tdata` - static and non static initialized thread local variables
 - `.tbss`  - static and non static non-initialized thread local variables

These exist in a special `TLS` [segment](https://lwn.net/Articles/531148/) which
is loaded per thread.  In the next article we will discuss more about how this
loading works.

## TLS Data Structures

As we recall, with the relocations used to access data in `.data` and `.bss`
sections simple code sequences with relocation entries are used.  These sequences
set and add registers to build pointers to our data.  For example, the below
sequence uses 2 relocations to compose a `.bss` section address into register
`r11`.

```
Addr.   Machine Code    Assembly             Relocations
0000000c <get_x_addr>:
   c:   19 60 [00 00]   l.movhi r11,[0]      # c  R_OR1K_AHI16 .bss
  10:   44 00  48 00    l.jr r9
  14:   9d 6b [00 00]    l.addi r11,r11,[0]  # 14 R_OR1K_LO_16_IN_INSN .bss
```

With TLS the code sequences to access our data will also build pointers to our
data, but they need to traverse the TLS data structures.

As the code sequence is read only and will be the same for each thread another
level of indirection is needed, this is provided by the Thread Pointer (TP).
The Thread Pointer is different for each thread.  On OpenRISC this is register
`r10` on x86_64 the `$fs` segment register is used.

The Thread Pointer points into a data structure that allows us to locate the
TLS sections.  The TLS data structure includes:

 - Thread Control Block (TCB)
 - Dynamic Thread Vector (DTV)
 - TLS Data Sections

These are illustrated as below:

```
  dtv[]   [ dtv[0], dtv[1], dtv[2], .... ]
            counter ^ |       \
               ----/  |        \________
              /       V                 V
/------TCB-------\   /----TLS[1]----\  /----TLS[2]----\
| pthread tcbhead |  | tbss   tdata |  | tbss   tdata |
\----------------/   \--------------/  \--------------/
          ^
          |
   TP-----/
```

### Thread Pointer (TP)

The Thread Pointer is unique to each thread.  It provides the starting point
to the TLS data structure.

 - On OpenRISC the value stored in `r10` (for x86_64 it's `$fs`) points to the
   Thread Control Block.
 - This is the `*tls` pointer passed to the
   [clone()](http://man7.org/linux/man-pages/man2/clone.2.html) system call when
   using `CLONE_SETTLS`.

### Tread Control Block (TCB)

The TCB is the head of the TLS data structure.  Each thread has a different
TCB and DTV.  The TCB consists of:

 - `pthread` - the [pthread](http://man7.org/linux/man-pages/man7/pthreads.7.html)
   struct for the current thread, contains `tid` etc. Located by `TP - TCB size - Pthread size`
 - `tcbhead` - the `tcbhead_t` struct, machine dependent, contains pointer to DTV.  Located by `TP - TCB size`.

For OpenRISC `tcbhead_t` is defined in
[sysdeps/or1k/nptl/tls.h](https://github.com/openrisc/or1k-glibc/blob/or1k-port/sysdeps/or1k/nptl/tls.h#L30) as:

```
typedef struct {
  dtv_t *dtv;
} tcbhead_t
```

 - `dtv` - is a pointer to the dtv array, points to entry `dtv[1]`

For x86_64 the `tcbhead_t` is defined in
[sysdeps/x86_64/nptl/tls.h](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/x86_64/nptl/tls.h;h=e7c1416eec4a490312ed56cc51a03a33eaa8e222;hb=HEAD#l42)
as:

```
typedef struct
{
  void *tcb;            /* Pointer to the TCB.  Not necessarily the
                           thread descriptor used by libpthread.  */
  dtv_t *dtv;
  void *self;           /* Pointer to the thread descriptor.  */
  int multiple_threads;
  int gscope_flag;
  uintptr_t sysinfo;
  uintptr_t stack_guard;
  uintptr_t pointer_guard;
  unsigned long int vgetcpu_cache[2];
  /* Bit 0: X86_FEATURE_1_IBT.
     Bit 1: X86_FEATURE_1_SHSTK.
   */
  unsigned int feature_1;
  int __glibc_unused1;
  /* Reservation of some values for the TM ABI.  */
  void *__private_tm[4];
  /* GCC split stack support.  */
  void *__private_ss;
  /* The lowest address of shadow stack,  */
  unsigned long long int ssp_base;
  /* Must be kept even if it is no longer used by glibc since programs,
     like AddressSanitizer, depend on the size of tcbhead_t.  */
  __128bits __glibc_unused2[8][4] __attribute__ ((aligned (32)));

  void *__padding[8];
} tcbhead_t;
```

The x86_64 implementation includes many more fields including:

 - `gscope_flag` - Global Scope lock flags used by the runtime linker, for OpenRISC this is stored in `pthread`.
 - `stack_guard` - The [stack
   guard](https://access.redhat.com/blogs/766093/posts/3548631) canary stored in
the thread local area.  For OpenRISC a global stack guard is stored in `.bss`.
 - `pointer_guard` - The [pointer
   guard](http://hmarco.org/bugs/glibc_ptr_mangle_weakness.html) stored in the
thread local area.  For OpenRISC a global pointer guard is stored in `.bss`.

### Dynamic Thread Vector (DTV)

The DTV is an array of pointers to each TLS (`.tbss`, `.tdata`) block of memory.
The first entry in the DTV array contains the generation counter.  The
generation counter is really just the array size.

The `dtv_t` type is a union as defined below:

```
typedef struct {
  void *val;     // Aligned pointer to data/bss
  void *to_free; // Unaligned pointer for free()
} dtv_pointer

typedef union {
  int counter;          // for entry 0
  dtv_pointer pointer;  // for all other entries
} dtv_t
```

Each `dtv_t` entry can be either a counter or a pointer.  By convention the
first entry, `dtv[0]` is a counter and the rest are pointers.

### Thread Local Storage (TLS)

The TLS data is allocated dynamically.  There will be one entry for each loaded
module, the first module being the current program.  For dynamic libraries it is
lazily initialized per thread.

#### Local (or TLS[1])
 - `tbss` - the `.tbss` section for the current thread from the current
   processes ELF binary.
 - `tdata` - the `.tdata` section for the current thread from the current
   processes ELF binary.

#### TLS[2]
 - `tbss` - the `.tbss` section for variables defined in the first shared library loaded by the current process
 - `tdata` - the `.tdata` section for the variables defined in the first shared library loaded by the current process

### The __tls_get_addr() function

The `__tls_get_addr()` function can be used at any time to traverse the TLS data
structure and return a variable's address.  The function is given a pointer to
and architecture specific argument `tls_index`.

  - The argument contains 2 pieces of data:
    - The module index - `0` for the current process, `1` for the first loaded shared
      library etc.
    - The data offset - the offset of the variable in the `TLS` data section
  - Internally `__tls_get_addr` uses TP to located the TLS data structure
  - The function returns the address of the variable we want to access

For static builds the implementation is architecture dependant and defined in
OpenRISC
[sysdeps/or1k/libc-tls.c](https://github.com/openrisc/or1k-glibc/blob/or1k-port/sysdeps/or1k/libc-tls.c#L28)
as:

```
__tls_get_addr (tls_index *ti)
{
  dtv_t *dtv = THREAD_DTV ();
  return (char *) dtv[1].pointer.val + ti->ti_offset;
}
```

Note for for static builds the module index can be hard coded to `1` as there
will always be only one module.

For dynamically linked programs the implementation is defined as part of the
runtime dynamic linker in
[elf/dl-tls.c](https://sourceware.org/git/?p=glibc.git;a=blob;f=elf/dl-tls.c;hb=9f8b135f76ac7943d1e108b7f6e816f526b2208c#l824)
as:

```
void *
__tls_get_addr (GET_ADDR_ARGS)
{
  dtv_t *dtv = THREAD_DTV ();

  if (__glibc_unlikely (dtv[0].counter != GL(dl_tls_generation)))
    return update_get_addr (GET_ADDR_PARAM);

  void *p = dtv[GET_ADDR_MODULE].pointer.val;

  if (__glibc_unlikely (p == TLS_DTV_UNALLOCATED))
    return tls_get_addr_tail (GET_ADDR_PARAM, dtv, NULL);

  return (char *) p + GET_ADDR_OFFSET;
}
```

Here several macros are used so it's a bit hard to follow but there are:

 - `THREAD_DTV` - uses TP to get the pointer to the DTV array.
 - `GET_ADDR_ARGS` - short for `tls_index* ti`
 - `GET_ADDR_PARAM` - short for `ti`
 - `GET_ADDR_MODULE` - short for `ti->ti_module`
 - `GET_ADDR_OFFSET` - short for `ti->ti_offset`

## TLS Access Models

As one can imagine, traversing the TLS data structures when accessing each variable
could be slow.  For this reason there are different TLS access models that the
compiler can choose to minimize the variable access overhead.

### Global Dynamic

The Global Dynamic access model is the slowest access model which will travese
the entire TLS data structure for each variable access.  It is used for
accessing variables in dynamic shared libraries.

#### Before Linking

![Global Dynamic Object](/content/2019/tls-gd-obj.png)

Before linking the `.text` contains 1 placeholder for offset for placeholder
info got which should contain 2 arguments to __tls_get_addr.

#### After Linking

![Global Dynamic Program](/content/2019/tls-gd-exe.png)


#### Example

File: [tls-gd.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-gd.c)

```
extern __thread int x;

int* get_x_addr() {
  return &x;
}
```

#### Code Sequence (OpenRISC)

```
tls-gd.o:     file format elf32-or1k

Disassembly of section .text:

0000004c <get_x_addr>:
  4c:	18 60 00 00 	l.movhi r3,[0]          # 4c: R_OR1K_TLS_GD_HI16	x
  50:	9c 21 ff f8 	l.addi r1,r1,-8
  54:	a8 63 00 00 	l.ori r3,r3,[0]         # 54: R_OR1K_TLS_GD_LO16	x
  58:	d4 01 80 00 	l.sw 0(r1),r16
  5c:	d4 01 48 04 	l.sw 4(r1),r9
  60:	04 00 00 02 	l.jal 68 <get_x_addr+0x1c>
  64:	1a 00 00 00 	 l.movhi r16,[0]        # 64: R_OR1K_GOTPC_HI16	_GLOBAL_OFFSET_TABLE_-0x4
  68:	aa 10 00 00 	l.ori r16,r16,[0]       # 68: R_OR1K_GOTPC_LO16	_GLOBAL_OFFSET_TABLE_
  6c:	e2 10 48 00 	l.add r16,r16,r9
  70:	04 00 00 00 	l.jal [0]               # 70: R_OR1K_PLT26	__tls_get_addr
  74:	e0 63 80 00 	 l.add r3,r3,r16
  78:	85 21 00 04 	l.lwz r9,4(r1)
  7c:	86 01 00 00 	l.lwz r16,0(r1)
  80:	44 00 48 00 	l.jr r9
  84:	9c 21 00 08 	 l.addi r1,r1,8
```

#### Code Sequence (x86_64)

```
tls-gd.o:     file format elf64-x86-64

Disassembly of section .text:

0000000000000020 <get_x_addr>:
  20:	48 83 ec 08          	sub    $0x8,%rsp
  24:	66 48 8d 3d 00 00 00 00 lea    [0](%rip),%rdi  # 28 R_X86_64_TLSGD	x-0x4
  2c:	66 66 48 e8 00 00 00 00 callq  [0]             # 30 R_X86_64_PLT32	__tls_get_addr-0x4
  34:	48 83 c4 08          	add    $0x8,%rsp
  38:	c3                   	retq   
```

### Local Dynamic

The Local Dynamic access model is an optimization for Global Dynamic where
multiple variables may be accessed from the same TLS module.  Instead of
traversing the TLS data structure for each variable, the TLS data section address
is loaded once by calling `__tls_get_addr` with an offset of `0`.  Next, variables
can be accessed with individual offsets.

Local Dynamic is not supported on OpenRISC yet.

#### Before Linking

![Local Dynamic Object](/content/2019/tls-ld-obj.png)

#### After Linking

![Local Dynamic Program](/content/2019/tls-ld-exe.png)

##### Example

File: [tls-ld.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-ld.c)

```
static __thread int x;
static __thread int y;

int sum() {
  return x + y;
}
```

#### Code Sequence (x86_64)

```
tls-ld.o:     file format elf64-x86-64

Disassembly of section .text:

0000000000000030 <sum>:
  30:	48 83 ec 08          	sub    $0x8,%rsp
  34:	48 8d 3d 00 00 00 00 	lea    [0](%rip),%rdi   # 37 R_X86_64_TLSLD	x-0x4
  3b:	e8 00 00 00 00       	callq  [0]              # 3c R_X86_64_PLT32	__tls_get_addr-0x4
  40:	8b 90 00 00 00 00    	mov    [0](%rax),%edx   # 42 R_X86_64_DTPOFF32	x
  46:	03 90 00 00 00 00    	add    [0](%rax),%edx   # 48 R_X86_64_DTPOFF32	y
  4c:	48 83 c4 08          	add    $0x8,%rsp
  50:	89 d0                	mov    %edx,%eax
  52:	c3                   	retq   
```
 
### Initial Exec

#### Before Linking

![Initial Exec Object](/content/2019/tls-ie-obj.png)

Text contains a placeholder for the got address of
the offset.


#### After Linking

![Initial Exec Program](/content/2019/tls-ie-exe.png)

Text contains the actual got offset, but the got value will be
resolved at runtime.

#### Example

File: [tls-ie.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-ie.c)

Initial exec c code will be the same as global dynamic, howerver IE access will
be chosed when static compiling or GD->IE relaxation is done during link time.

```
extern __thread int x;

int* get_x_addr() {
  return &x;
}
```

#### Code Sequence (OpenRISC)

```
00000038 <get_x_addr>:
  38:	9c 21 ff fc 	l.addi r1,r1,-4
  3c:	1a 20 00 00 	l.movhi r17,0x0
			3c: R_OR1K_TLS_IE_AHI16	x
  40:	d4 01 48 00 	l.sw 0(r1),r9
  44:	04 00 00 02 	l.jal 4c <get_x_addr+0x14>
  48:	1a 60 00 00 	l.movhi r19,0x0
			48: R_OR1K_GOTPC_HI16	_GLOBAL_OFFSET_TABLE_-0x4
  4c:	aa 73 00 00 	l.ori r19,r19,0x0
			4c: R_OR1K_GOTPC_LO16	_GLOBAL_OFFSET_TABLE_
  50:	e2 73 48 00 	l.add r19,r19,r9
  54:	e2 31 98 00 	l.add r17,r17,r19
  58:	85 71 00 00 	l.lwz r11,0(r17)
			58: R_OR1K_TLS_IE_LO16	x
  5c:	85 21 00 00 	l.lwz r9,0(r1)
  60:	e1 6b 50 00 	l.add r11,r11,r10
  64:	44 00 48 00 	l.jr r9
  68:	9c 21 00 04 	l.addi r1,r1,4
```

#### Code Sequence (x86_64)

```
0000000000000010 <get_x_addr>:
  10:	48 8b 05 00 00 00 00 	mov    0x0(%rip),%rax        # 17 <get_x_addr+0x7>
			13: R_X86_64_GOTTPOFF	x-0x4
  17:	64 48 03 04 25 00 00 	add    %fs:0x0,%rax
  1e:	00 00 
  20:	c3                   	retq   
```

### Local Exec

#### Before Linking

![Local Exec Object](/content/2019/tls-le-obj.png)

#### After Linking

![Local Exec Program](/content/2019/tls-le-exe.png)

#### Example

File: [tls-le.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-le.c)

```
static __thread int x;

int* get_x_addr() {
  return &x;
}
```

#### Code Sequence (OpenRISC)

```
00000010 <get_x_addr>:
  10:	19 60 00 00 	l.movhi r11,0x0
			10: R_OR1K_TLS_LE_AHI16	.LANCHOR0
  14:	e1 6b 50 00 	l.add r11,r11,r10
  18:	44 00 48 00 	l.jr r9
  1c:	9d 6b 00 00 	l.addi r11,r11,0
			1c: R_OR1K_TLS_LE_LO16	.LANCHOR0
```

#### Code Sequence (x86_64)

```
0000000000000010 <get_x_addr>:
  10:	64 48 8b 04 25 00 00	mov    %fs:0x0,%rax
  17:	00 00 
  19:	48 05 00 00 00 00    	add    $0x0,%rax
			1b: R_X86_64_TPOFF32	x
  1f:	c3                   	retq   
```

## Relocation Relaxation

As some TLS access methods are more efficient than others we would like to choose
the best method for each variable access.  However, we don't
always know where a variable will come from until link time.

On some architectures the linker will rewrite the TLS access code sequence to
change to a more efficient access model, this is called relaxation.

One time of relaxation performed by the linker is GD to IE relaxation.  During compile
time GD relocation may be choosen for `extern` variables.  However, during link time
the variable may be in the same module i.e. not a shared object which would require
GD access.

That's pretty cool.

## Summary

In the next article we will look more into how this is implemented in GCC, the
linker and the GLIBC runtime dynamic linker.

## Further Reading
- Fuschia - https://fuchsia.dev/fuchsia-src/development/threads/tls
- Bottums Up - http://bottomupcs.sourceforge.net/csbu/x3735.htm
- GOT and PLT - https://www.technovelty.org/linux/plt-and-got-the-key-to-code-sharing-and-dynamic-libraries.html
- Android - https://android.googlesource.com/platform/bionic/+/HEAD/docs/elf-tls.md
- Oracle - https://docs.oracle.com/cd/E19683-01/817-3677/chapter8-20/index.html
- Drepper - https://www.akkadia.org/drepper/tls.pdf
- Deep Dive - https://chao-tic.github.io/blog/2018/12/25/tls
