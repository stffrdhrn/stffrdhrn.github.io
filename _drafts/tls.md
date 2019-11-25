
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

## ELF Segments and Sections

Before we can talk about relocations we need to talk a bit about what makes up
[ELF](https://en.wikipedia.org/wiki/Executable_and_Linkable_Format) binaries.
This is a prerequisite as relocations and TLS are part of ELF binaries.  There
are a few basic ELF binary types:

 - Objects (`.o`) - produced by a compiler, contains a collection of sections, also call relocatable files.
 - Program - an executable program, contains sections grouped into segments.
 - Shared Objects (`.so`) - a program library, contains sections grouped into segments.
 - Core Files - core dump of program memory, these are also ELF binaries

Here we will discuss Object Files and Program Files.

### An ELF Object

![ELF Object](/content/2019/elf-obj.png)

The compiler generates object files, these contain sections of binary data and
these are not executable.

The object file produced by [gcc](https://gcc.gnu.org/onlinedocs/gcc-9.2.0/gcc/Overall-Options.html#index-c)
generally contains `.rela.text`, `.text`, `.data` and `.bss` sections.

 - `.rela.text` - a list of relocations against the `.text` section
 - `.text` - contains compiled program machine code
 - `.data` - static and non static initialized variable values
 - `.bss`  - static and non static non-initialized variables

### An ELF Program

![ELF Program](/content/2019/elf-program.png)

ELF binaries are made of [sections](https://en.wikipedia.org/wiki/Data_segment) and segments.

A segment contains a group of sections and the segment defines how the data should
be loaded into memory for program execution.

Each segment is mapped to program memory by the kernel when a process is created.  Program files contain
most of the same sections as objects but there are some differences.

 - `.text` - contains executable program code, there is no `.rela.text` section
 - `.got`  - the [global offset table](https://en.wikipedia.org/wiki/Global_Offset_Table) used to access variables, created during link time.  May be populated during runtime.

### Looking at ELF binaries (`readelf`)

The `readelf` tool can help inspect elf binaries.

Some examples:

#### Reading Sections of an Object File

Using the `-S` option we can read sections from an elf file.
As we can see below we have the `.text`, `.rela.text`, `.bss` and many other
sections.

```
$ readelf -S tls-le-static.o
There are 20 section headers, starting at offset 0x604:

Section Headers:
  [Nr] Name              Type            Addr     Off    Size   ES Flg Lk Inf Al
  [ 0]                   NULL            00000000 000000 000000 00      0   0  0
  [ 1] .text             PROGBITS        00000000 000034 000020 00  AX  0   0  4
  [ 2] .rela.text        RELA            00000000 0003f8 000030 0c   I 17   1  4
  [ 3] .data             PROGBITS        00000000 000054 000000 00  WA  0   0  1
  [ 4] .bss              NOBITS          00000000 000054 000000 00  WA  0   0  1
  [ 5] .tbss             NOBITS          00000000 000054 000004 00 WAT  0   0  4
  [ 6] .debug_info       PROGBITS        00000000 000054 000074 00      0   0  1
  [ 7] .rela.debug_info  RELA            00000000 000428 000084 0c   I 17   6  4
  [ 8] .debug_abbrev     PROGBITS        00000000 0000c8 00007c 00      0   0  1
  [ 9] .debug_aranges    PROGBITS        00000000 000144 000020 00      0   0  1
  [10] .rela.debug_arang RELA            00000000 0004ac 000018 0c   I 17   9  4
  [11] .debug_line       PROGBITS        00000000 000164 000087 00      0   0  1
  [12] .rela.debug_line  RELA            00000000 0004c4 00006c 0c   I 17  11  4
  [13] .debug_str        PROGBITS        00000000 0001eb 00007a 01  MS  0   0  1
  [14] .comment          PROGBITS        00000000 000265 00002b 01  MS  0   0  1
  [15] .debug_frame      PROGBITS        00000000 000290 000030 00      0   0  4
  [16] .rela.debug_frame RELA            00000000 000530 000030 0c   I 17  15  4
  [17] .symtab           SYMTAB          00000000 0002c0 000110 10     18  15  4
  [18] .strtab           STRTAB          00000000 0003d0 000025 00      0   0  1
  [19] .shstrtab         STRTAB          00000000 000560 0000a1 00      0   0  1
```

#### Reading Sections of a Program File

Using the `-S` option on a program file we can also read the sections.  The file
type does not matter as long as it is an ELF we can read the sections.
As we can see below there is no longer a `rela.text` section, but we have others
including the `.got` section.

```
$ readelf -S tls-le-static
There are 31 section headers, starting at offset 0x32e8fc:

Section Headers:
  [Nr] Name              Type            Addr     Off    Size   ES Flg Lk Inf Al
  [ 0]                   NULL            00000000 000000 000000 00      0   0  0
  [ 1] .text             PROGBITS        000020d4 0000d4 080304 00  AX  0   0  4
  [ 2] __libc_freeres_fn PROGBITS        000823d8 0803d8 001118 00  AX  0   0  4
  [ 3] .rodata           PROGBITS        000834f0 0814f0 01544c 00   A  0   0  4
  [ 4] __libc_subfreeres PROGBITS        0009893c 09693c 000024 00   A  0   0  4
  [ 5] __libc_IO_vtables PROGBITS        00098960 096960 0002f4 00   A  0   0  4
  [ 6] __libc_atexit     PROGBITS        00098c54 096c54 000004 00   A  0   0  4
  [ 7] .eh_frame         PROGBITS        00098c58 096c58 0027a8 00   A  0   0  4
  [ 8] .gcc_except_table PROGBITS        0009b400 099400 000089 00   A  0   0  1
  [ 9] .note.ABI-tag     NOTE            0009b48c 09948c 000020 00   A  0   0  4
  [10] .tdata            PROGBITS        0009dc28 099c28 000010 00 WAT  0   0  4
  [11] .tbss             NOBITS          0009dc38 099c38 000024 00 WAT  0   0  4
  [12] .init_array       INIT_ARRAY      0009dc38 099c38 000004 04  WA  0   0  4
  [13] .fini_array       FINI_ARRAY      0009dc3c 099c3c 000008 04  WA  0   0  4
  [14] .data.rel.ro      PROGBITS        0009dc44 099c44 0003bc 00  WA  0   0  4
  [15] .data             PROGBITS        0009e000 09a000 000de0 00  WA  0   0  4
  [16] .got              PROGBITS        0009ede0 09ade0 000064 04  WA  0   0  4
  [17] .bss              NOBITS          0009ee44 09ae44 000bec 00  WA  0   0  4
  [18] __libc_freeres_pt NOBITS          0009fa30 09ae44 000014 00  WA  0   0  4
  [19] .comment          PROGBITS        00000000 09ae44 00002a 01  MS  0   0  1
  [20] .debug_aranges    PROGBITS        00000000 09ae6e 002300 00      0   0  1
  [21] .debug_info       PROGBITS        00000000 09d16e 0fd048 00      0   0  1
  [22] .debug_abbrev     PROGBITS        00000000 19a1b6 0270ca 00      0   0  1
  [23] .debug_line       PROGBITS        00000000 1c1280 0ce95c 00      0   0  1
  [24] .debug_frame      PROGBITS        00000000 28fbdc 0063bc 00      0   0  4
  [25] .debug_str        PROGBITS        00000000 295f98 011e35 01  MS  0   0  1
  [26] .debug_loc        PROGBITS        00000000 2a7dcd 06c437 00      0   0  1
  [27] .debug_ranges     PROGBITS        00000000 314204 00c900 00      0   0  1
  [28] .symtab           SYMTAB          00000000 320b04 0075d0 10     29 926  4
  [29] .strtab           STRTAB          00000000 3280d4 0066ca 00      0   0  1
  [30] .shstrtab         STRTAB          00000000 32e79e 00015c 00      0   0  1
Key to Flags:
  W (write), A (alloc), X (execute), M (merge), S (strings), I (info),
  L (link order), O (extra OS processing required), G (group), T (TLS),
  C (compressed), x (unknown), o (OS specific), E (exclude),
  p (processor specific)
```

#### Reading Segments from a Program File

Using the `-l` option on a program file we can read the segments.
Notice how segments map from file offsets to memory offsets and alignment.
The two different `LOAD` type segments are segregated by read only/execute and read/write.
Each section is also mapped to a segment here.  As we can see `.text is in the first `LOAD` segment
which is executable as expected.

```
$ readelf -l tls-le-static

Elf file type is EXEC (Executable file)
Entry point 0x2104
There are 5 program headers, starting at offset 52

Program Headers:
  Type           Offset   VirtAddr   PhysAddr   FileSiz MemSiz  Flg Align
  LOAD           0x000000 0x00002000 0x00002000 0x994ac 0x994ac R E 0x2000
  LOAD           0x099c28 0x0009dc28 0x0009dc28 0x0121c 0x01e1c RW  0x2000
  NOTE           0x09948c 0x0009b48c 0x0009b48c 0x00020 0x00020 R   0x4
  TLS            0x099c28 0x0009dc28 0x0009dc28 0x00010 0x00034 R   0x4
  GNU_RELRO      0x099c28 0x0009dc28 0x0009dc28 0x003d8 0x003d8 R   0x1

 Section to Segment mapping:
  Segment Sections...
   00     .text __libc_freeres_fn .rodata __libc_subfreeres __libc_IO_vtables __libc_atexit .eh_frame .gcc_except_table .note.ABI-tag 
   01     .tdata .init_array .fini_array .data.rel.ro .data .got .bss __libc_freeres_ptrs 
   02     .note.ABI-tag 
   03     .tdata .tbss 
   04     .tdata .init_array .fini_array .data.rel.ro 
```

#### Reading Segments from an Object File

Using the `-l` option with an object file does not work as we can see below.

```
readelf -l tls-le-static.o

There are no program headers in this file.
```

## Relocation entries

As mentioned an object file by itself is not executable.  The main reason is that
there are no program headers as we just saw.  Another reason is that
the `.text` section still contains relocation entries (or placeholders) for the
addresses of variables located in the `.data` and `.bss` sections.
These placeholders will just be `0` in the machine code.  So, if we tried to run
the machine code in an object file we would end up with Segmentation faults ([SEGV](https://en.wikipedia.org/wiki/Segmentation_fault)).

A relocation entry is a placeholder that is added by the compiler or linker when
producing ELF binaries.
The relocation entries are to be filled in with addresses pointing to data.
Relocation entries can be made in code such as the `.text` section or in data
sections like the `.got` section.  For example:

## Resolving Relocations

![GCC and Linker](/content/2019/gcc-obj-ld.png)

The diagram above shows relocation entries as white circles.
Relocation entries may be filled at link time or dynamically during execution.

Link time relocations
  - Place holders are filled in when ELF object files are linked by the linker to create executables or libraries
  - For example, relocation entries in `.text` sections

Dynamic relocations
  - Place holders is filled during runtime by the dynamic linker.  i.e. Procedure Link Table
  - For example, relocation entries added to `.got` and `.plt` sections which link
    to shared objects.

In general link time relocations are used to fill in relocation entries in code.
Dynamic relocations fill in relocation entries in data sections.

### Listing Relocation Entries

A list of relocations in a ELF binary can printed using `readelf` with
the `-r` options.

Output of `readelf -r tls-gd-dynamic.o`

```
Relocation section '.rela.text' at offset 0x530 contains 10 entries:
 Offset     Info    Type            Sym.Value  Sym. Name + Addend
...
0000002c  00000d0f R_OR1K_PLT26      00000000   __tls_get_addr + 0
```

The relocation entry list explains how to and where to apply the relocation entry.
It contains:
 - `Offset` - the location in the binary that needs to be updated
 - `Info` - the encoded value containing the `Type, Sym and Addend`, which is
     broken down to:
   - `Type` - the type of relocation (the formula for what is to be performed is defined in the
     linker)
   - `Sym. Value` - the address value (if known) of the symbol in the symbol table.
   - `Sym. Name` - the name of the symbol (variable name) that this relocation needs to find
     during link time.
 - `Addend` - an value that needs to be added to the derived symbol address.
   This is used to with arrays (i.e. for a relocation referencing `a[14]` we would have **Sym. Name** `a` an **Addend** of the data size of `a` times `14`)

### Example

File: [nontls.c](https://github.com/stffrdhrn/tls-examples/blob/master/nontls.c)

In the example below we have a simple static variable that when compiled will
contain a relocation entry as a placeholder to the actual location in memory:

```
static int x;

int* get_x_addr() {
  return &x;
}
```

The steps to compile and link can be found in the `tls-examples` project hosting
the source examples.


### Compiler Output

In the output below we can see that access to the variable `x` which is in `.bss`
is referenced by a literal `0` in each instruction.  These bits are to be filled in during
linking stage to provide access to the actual variable addresses.

These empty parts of the `.text` section are relocation entries.

```
0000000c <get_x_addr>:
   c:   19 60 00 00     l.movhi r11,[0]      # c  R_OR1K_AHI16 .bss
  10:   44 00 48 00     l.jr r9
  14:   9d 6b 00 00      l.addi r11,r11,[0]  # 14 R_OR1K_LO_16_IN_INSN        .bss
```

After linking the `0` values will be replaced with actuall offset values, there
will be no relocations left.

The instructions can be understood as follows (take note that openrisc has a branch delay
slot, meaning the instruction after the branch is run before the branch is take).

 - `l.movhi` - move the value `[0]` into high bits of register `r11`, clearing the lower bits.
 - `l.addi` - add the value in register `r11` to the value `[0]` and store the results in `r11`.
 - `l.jr` - jump the the address in `r9`

This constructs the address of `x` out of 2 16-bit values into `r11`, `r11` is the function
return value register in openrisc.  It then returns from the function as `r9` is the link register.

### Linker output

As we can see from the linker output the places in the code that had relocation place holders
are now replaced with values.  For example `1a 20 00 00` has become `1a 20 00 0a`.

```
00002298 <get_x_addr>:
    2298:	19 60 00 0a 	l.movhi r11,0xa
    229c:	44 00 48 00 	l.jr r9
    22a0:	9d 6b ee 60 	l.addi r11,r11,-4512
```

If we calculate `0xa << 16 + -4512 (fee60)` we see get `0009ee60`.  That is the
same location of `x` within our binary.  This we can check with `readelf -s`
which lists all symbols.

```
$ readelf -s nontls-static | grep ' x'
    42: 0009ee60     4 OBJECT  LOCAL  DEFAULT   17 x
```

## Types of Relocations

As we saw above, a simple program resulted in 2 different relocation entries just to compose the address of 1 variable.
We saw:

  - `R_OR1K_AHI16`
  - `R_OR1K_LO_16_IN_INSN`

The need for different relation types comes from the different requirements for the
relocation.  Processing of a relocation involves usually a very simple transform
, each relocation defines a different tansform.

  - The input of a relocation is usually a variable address that is unknown
    at compile time.
  - The translation involves manipulating the address as required by the machine
    code in some way.  For example take the upper 16 bits of the address.
  - The output of a relocation is placing the transformed value into machine
    code text.

To be more specific about the above relocations we have:

  - `R_OR1K_AHI16` - take the upper 16 bits of input and place in the lower 16 bits of the destination
  - `R_OR1K_LO_16_IN_INSN` - take the lower 16 bits of input and place in the lower 16 bits of the destination

These use different methods due what each instruction does, and where each instruction
encodes its immediate value.

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
