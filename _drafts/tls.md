
# ELF Binary Relocations and TLS

Recently I have been working on getting the [OpenRISC glibc](https://github.com/openrisc/or1k-glibc)
port ready for upstreaming.  Part of this work has been to run the glibc
testsuite and get the tests to pass.  The [glibc testsuite](https://sourceware.org/glibc/wiki/Testing/Testsuite)
has a comprehensive set of linker and runtime relocation tests.

In order to fix issues with tests I had to learn more than I did before about ELF Relocations
, Thread Local Storage and the binutils linker implementation in BFD.  There is a lot of
documentation available, but it's a bit hard to follow as it assumes certain
knowledge, for example have a look at the Solaris [Linker and Libraries](https://docs.oracle.com/cd/E23824_01/html/819-0690/chapter6-54839.html)
section on relocations.  In this article I will try to fill in those gaps.

 - Why are they needed and how do they work?
    - Relocations
    - TLS

 - What do the ELF binaries look like from GCC to the Linker into our final executable?

 - How are they implemented?
    - In GCC?
    - In the Linker?
    - in GLIBC?

We will attempt to answer these in this illustrated article.

All of the examples in this article can be found in my [tls-examples](https://github.com/stffrdhrn/tls-examples)
project.  Please check it out.

On Linux, you can download it and `make` it with your favorite toolchain.
By default it will cross compile using an [openrisc toolchain](https://openrisc.io/software).
This can be overridden with the `CROSS_COMPILE` variable.
For example, to build for your current host.

```
$ git clone git@github.com:stffrdhrn/tls-examples.git
$ make CROSS_COMPILE=
gcc -fpic -c -o tls-gd-dynamic.o tls-gd.c -Wall -O2 -g
gcc -fpic -c -o nontls-dynamic.o nontls.c -Wall -O2 -g
...
objdump -dr x-static.o > x-static.S
objdump -dr xy-static.o > xy-static.S
```

Now we can get started.

## Thread Local Storage

Did you know that in a C or C++ you could prefix variables with `__thread` or
`thread_local` respectively?  These prefixes are used to create thread local variables.

### Example

C:

```
__thread int i;
```

C++:

```
thread_local int i;
```

A thread local variable is a variable that will have a unique value per thread.
Each time a new thread is created, the space required to store the thread local
variables is allocated.

TLS variables are stored in dynamic TLS sections.

## TLS Sections

When we get to binaries that use TLS we will also have `.tdata` and `.tbss`.  Each section
is dynamically allocated into the process heap during runtime once per each thread.

 - `.tdata` - static and non static initialized thread local variables
 - `.tbss`  - static and non static non-initialized thread local variables

These exist in a special `TLS` segment which is loaded per thread.

## TLS memory layout

In order to reference data in the `.tdata` and `.tbss` sections code sequences
with relocations are used.  With the non thread local `.data` and `.bss` sections
the address offsets to to index into the data segments are program counter relative
or absolute addresses.  For to access data in thread local data sections address
offsets are relative to something called the Thread Pointer (TP).  On OpenRISC this
is register `r10` on x86_64 the `$fs` segment register is used.

The Thread Pointer points into a data structure that contains multiple bits of data.
This includes the Dynamic Thread Vector (DTV) an array of pointers to thread
data sections.  The Thread Control Block a structure which points to the DTV, it is followed
by the first thread local data section and proceeded by the pthread structure.

```
           INSTALL_DTV
              |
              |         INSTALL_NEW_DTV
              V         V
  dtv[]   [ dtv[0], dtv[1], dtv[2], .... ]
            counter ^ |       \
               ----/  |        \_____
              /       V              V
/------TCB-------\/----TLS[0]-----\/----TLS[1]-----\/--...
| pthread tcbhead | tbss    tdata | tbss   tdata   |   ...
\----------------/\---------------/\---------------/\--...
          ^
          |
   TP-----/
```

### Thread Pointer (TP)

The Thread Pointer is different for each thread.

 - The value stored in `r10` points to the Initial Block

### Tread Control Block (TCB)

Contains the data pointed to by the Thread Pointer.  Each thread has a different
TCB and DTV.  The TCB consists of:

 - `pthread` - the `pthread` struct for the current thread, contains tid etc. Located by `TP - TCB size - Pthread size`
 - `tcbhead` - the `tcbhead_t` struct, machine dependent, contains pointer to DTV.  Located by `TP - TCB size`.

TCB for openrisc:

```
typedef struct {
  dtv_t *dtv;
} tcbhead_t
```
 - `dtv` - is a pointer to the dtv array, points to entry `dtv[1]`

### Dynamic Thread Vector (DTV)
The DTV array of pointers to each TLS (`.tbss`, `.tdata`) block of memory.  The
first entry in the DTV array contains the generation counter.  The generation
counter is usually indexed as DTV[-1] as is it the entry pointed to before where
the TCB head pointer points.  The generation counter is really just the vector
size.

The `dtv_t` union

```
typedef struct {
  void * val; // Points to data/bss
} dtv_pointer

typedef union {
  int counter;          // for entry 0
  dtv_pointer pointer;  // for all other entries
} dtv_t
```

Each `dtv_t` entry can be either a counter of a pointer.  By convention the first entry, `dtv[0]` is a counter and
the rest are pointers.

### Local (or TLS[1])
 - `tbss` - the `.tbss` section for the current thread from the current process
 - `tdata` - the `.tdata` section for the current thread from the current process

### TLS[2]
 - `tbss` - the `.tbss` section for variables defined in the first shared library loaded by the current process
 - `tdata` - the `.tdata` section for the variables defined in the first shared library loaded by the current process

## Setting up the TCB

The below macros defined in `tls.h` for OpenRISC and provide the functionality to setup
the TCB and DTV.

### INSTALL_DTV

```
#define INSTALL_DTV(tcbp, dtvp) (((tcbhead_t *) (tcbp))->dtv = (dtvp) + 1)
```

During the initial thread structure allocation.

  - dtvp+1 means we want the TCB to point to point to dtv[1]
  - called to set dtv in the control block

### TLS_INIT_TP

```
#define TLS_INIT_TP(tcbp)  __thread_self = ((tcbhead_t *)tcbp + 1);
```

After all allocation is done we set `__thread_self`, the Thread Pointer.

  - (tcbp + 1) means we want to point just after tcb

### THREAD_DTV

```
#define THREAD_DTV ((((tcbhead_t *)__thread_self)-1)->dtv)
```

Alias for the current threads DTV pointer.

### INSTALL_NEW_DTV

```
#define INSTALL_NEW_DTV(dtv) (THREAD_DTV() = (dtv))
```

During runtime if resizing of the DTV array is needed this is called
to update the TCB DTV pointer.

 - when called the passed dtv is actually dtv[1], this is confusing as it is not consistent with `INSTALL_DTV`

### The function `__tls_get_addr()`

The `__tls_get_addr()` can be used at any time to traverse the TCB and return a variables
address given the module index and thread local data section offset.

  - takes to args mod index, offset
  - interally uses TP
  - Returns the address of the variable we want to access

The implementation is:

```
// Psuedo Code
__tls_get_addr(int mod_index, int offset) {
  TCB = TP - (TCB SIZE);
  DTV = TCB->dtv;
  return DTV[mod_index].pointer.val + offset;
}

// Real Code
__tls_get_addr (tls_index *ti)
{
  dtv_t *dtv = THREAD_DTV ();

  void *p = dtv[ti->ti_module].pointer.val;

  return (char *) p + ti->ti_offset;
}
```

## Global Dynamic

### Before Linking

![Global Dynamic Object](/content/2019/tls-gd-obj.png)

Before linking the `.text` contains 1 placeholder for offset for placeholder
info got which should contain 2 arguments to __tls_get_addr.

### After Linking

![Global Dynamic Program](/content/2019/tls-gd-exe.png)


### Example

File: [tls-gd.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-gd.c)

```
extern __thread int x;

int* get_x_addr() {
  return &x;
}
```

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

Example on x86

```
gcc -O3 -fpic -g -c tls-gd.c
objdump -dr tls-gd.o
```

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

## Local Dynamic

No supported on openrisc

### Before Linking

![Local Dynamic Object](/content/2019/tls-ld-obj.png)

### After Linking

![Local Dynamic Program](/content/2019/tls-ld-exe.png)

### Example

File: [tls-ld.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-ld.c)

```
static __thread int x;
static __thread int y;

int sum() {
  return x + y;
}
```

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
 
## Initial Exec

### Before Linking

![Initial Exec Object](/content/2019/tls-ie-obj.png)

Text contains a placeholder for the got address of
the offset.


### After Linking

![Initial Exec Program](/content/2019/tls-ie-exe.png)

Text contains the actual got offset, but the got value will be
resolved at runtime.

### Example

File: [tls-ie.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-ie.c)

Initial exec c code will be the same as global dynamic, howerver IE access will
be chosed when static compiling or GD->IE relaxation is done during link time.

```
extern __thread int x;

int* get_x_addr() {
  return &x;
}
```


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

```
0000000000000010 <get_x_addr>:
  10:	48 8b 05 00 00 00 00 	mov    0x0(%rip),%rax        # 17 <get_x_addr+0x7>
			13: R_X86_64_GOTTPOFF	x-0x4
  17:	64 48 03 04 25 00 00 	add    %fs:0x0,%rax
  1e:	00 00 
  20:	c3                   	retq   
```

## Local Exec

### Before Linking

![Local Exec Object](/content/2019/tls-le-obj.png)

### After Linking

![Local Exec Program](/content/2019/tls-le-exe.png)

### Example

File: [tls-le.c](https://github.com/stffrdhrn/tls-examples/blob/master/tls-le.c)

```
static __thread int x;

int* get_x_addr() {
  return &x;
}
```

```
00000010 <get_x_addr>:
  10:	19 60 00 00 	l.movhi r11,0x0
			10: R_OR1K_TLS_LE_AHI16	.LANCHOR0
  14:	e1 6b 50 00 	l.add r11,r11,r10
  18:	44 00 48 00 	l.jr r9
  1c:	9d 6b 00 00 	l.addi r11,r11,0
			1c: R_OR1K_TLS_LE_LO16	.LANCHOR0
```

```
0000000000000010 <get_x_addr>:
  10:	64 48 8b 04 25 00 00 	mov    %fs:0x0,%rax
  17:	00 00 
  19:	48 05 00 00 00 00    	add    $0x0,%rax
			1b: R_X86_64_TPOFF32	x
  1f:	c3                   	retq   
```

## TLS Relocation Summary

### Handling Shared vs Static Linking

  - Shared - got + rela, for runtime linking i.e.
      - R_OR1K_TLS_DTPMOD - module index
      - R_OR1K_TLS_DTPOFF - offset in module tbss
  - Static - got only



## BFD

htab - stores arch specific details
local_tls_type - store tls_type if no h
h - store detail for each symbol, how many got entries required 
   got.offset where in the got the value is
```
#define elf_backend_relocate_sectio nor1k_elf_relocate_section
  - For symbol, write got section, write rela if dynamic
     - Write entry to GOT only once per symbol
  - run or1k_final_link_relocate
     - Write actual value to text

#define elf_backend_create_dynamic_sections 
#define elf_backend_finish_dynamic_sections
#define elf_backend_size_dynamic_sections

or1k_elf_check_relocs - loop through relocations and do book keeping
  - 
```

### GCC

In gcc we have riscv_legitimize_tls_address.  It takes an address of a variable
and generates code to load that address based on variable properties.

Global Variable - code will be GOTBASE + offset - the offset is determined at link time

 - GD - code will load MOD and OFF from GOT and call __tls_get_addr
 - LD - code will load a base with __tls_get_addr, then use local offsets for subsequent calls to load variable addresses.  Useful if more than one variables are accessed, it saves from having to do multiple calls to __tls_get_addr.
 - IE - code will load OFFSET got  and add to TP directly
    - tp = TP
    - tmp = load ie from GOT
    - res = tmp + tp
 - LOCAL - code wil$l have OFFSET directly + TP directly - offset is determined as link time
    - tmp = tls offset
    - res = tp + tmp

## Relaxation

As some TLS access methods are more efficient than others we would like to choose
the best method for each variable access.  However, we don't
always know where a variable will come from until link time.

One time of relaxation performed by the linker is GD to IE relaxation.  During compile
time GD relocation may be choosen for `extern` variables.  However, during link time
the variable may be in the same module i.e. not a shared object which would require
GD access.

If relaxation can be done.
Relaxation will rewrite the GD access code in the .text section of the binary and
convert it to IE access.


## Further Reading
- Bottums Up - http://bottomupcs.sourceforge.net/csbu/x3735.htm
- GOT and PLT - https://www.technovelty.org/linux/plt-and-got-the-key-to-code-sharing-and-dynamic-libraries.html
- Android - https://android.googlesource.com/platform/bionic/+/HEAD/docs/elf-tls.md
- Oracle - https://docs.oracle.com/cd/E19683-01/817-3677/chapter8-20/index.html
- Drepper - https://www.akkadia.org/drepper/tls.pdf
- Deep Dive - https://chao-tic.github.io/blog/2018/12/25/tls
