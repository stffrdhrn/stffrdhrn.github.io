
What is TLS?

Thread Local Storage

```
__thread int i;
```

A variable per thread.  

Today we want to talk about how TLS relocations work.  To get started lets talk
a bit about background items.  In order to understand relocations we need to know
a bit about how ELF binaries work.

## Segments

ELF binaries are made of sections and segments.

The .o file produced by gcc contains only the .text and .data and.bss sections.?

 .text
 .data - static initialized variables
 .bss  - static non-initialized variables
 .got  - for PIC access to variables, created during link time

When we get to binaries that use TLS we will also have

 .tdata
 .tbss

## Relocations

A relocation is a placeholder that is added by the compiler when creating object
files and then filled in by the linker.  There are
two types of relocations.  Link time relocations, dynamic relocations.

Link time relocation
  - Place holder filled in when .o files are linked into executable

Dymaic link relocations
  - Place holder is filled during runtime.  i.e. PLT link to .so

```

 int i = 5;

```

# TLS memory layout

```

dtv[]
  INSTALL_DTV
  |
  |         INSTALL_NEW_DTV
  V         V
[ dtv[0] , dtv[1] , dtv[2], .... ]
  counter   |       ¥
            |        ¥_____
            V              V
/--------¥/---Local------¥/----mod2-------¥
|ppre tcb| tbss | tdata | tbss | tdata   |
          ^
          |
   TP-----¥
```

pre - the pthread struct, contains tid etc
tcb - the tcbhead_t, machine dependent for openrisc

```
struct {
  dtv_t *dtv;
}
```

dtv_t - is a pointer to all of the bss and data sections?

```
typedef struct {
  void * val; // Points to data/bss
} dtv_pointer

typedef union {
  int counter;          // for entry 0
  dtv_pointer pointer;  // for all other entries
} dtv_t
```

During thread structure allocation, dtvp+1 means we point to dtv[1]
INSTALL_DTV     = (((tcbhead_t *) (tcbp))->dtv = (dtvp) + 1)
  - called to set dtv, given tcb and dtv

After all allocation is done, (tcbp + 1) allow pointing to just after tcb
TLS_INIT_TP     = __thread_self = ((tcbhead_t *)tcbp + 1); 

During runtime if resize is needed, now passed in dtv is actually dtv[1]?
INSTALL_NEW_DTV = (THREAD_DTV() = (dtv))
  - called to resize. given only dtv

THREAD_DTV      = ((((tcbhead_t *)__thread_self)-1)->dtv)

# The function - `__tls_get_addr()`

  - takes to args mod index, offset
  - interally uses TP
  - Returns the address of the variable we want to access

## Global Dynamic

```

Before link text contains 1 placeholder for offset for placeholder
info got which should contain 2 arguments to __tls_get_addr.

----text----
|          |
| a []     |  R_OR1K_TLS_GD_HI16,R_OR1K_TLS_GD_LO16
|          |
-----------|

After Link

 ---text----   ---got---       --rela------
|           | |         |     |            |
| a [---------> mod [ ] |<----| TLS_DTPMOD |
|___________| | off [ ] |<----| TLS_DTPOFF |
              |_________|     |____________|
                      |
                      ==============|-----|
                                    V     V
                     __tls_get_addr(mod, off)

```

Example:

```
extern __thread int x;

int* get_x_addr() {
  return &x;
}
```

Compile and disassembly

```
or1k-smh-linux-gnu-gcc -O3 -fpic -g -c tls-gd.c
or1k-smh-linux-gnu-objdump -dr tls-gd.o
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

```
static __thread int x;
static __thread int y;

int sum() {
  return x + y;
}
```

```
gcc -O3 -fpic -g -c tls-ld.c
objdump -dr tls-ld.o
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

```
Before Link

----text----
|          |
| a []     |
|          |
-----------|

Text contains a placeholder for the got address of
the offset.

After Link

----text----   ---got---      ---rela------
|           | |         |     |            |
| a [--------->         |     |            |
|___________| | off [ ] |<----| TLS_DTPOFF |
              |_________|     |____________|

   tp + off

Text contains the actual got offset, but the got value will be
resolved at runtime.

```

Local Exec

```
Before Link

----text-------
|              |
|  a = tp + [] |  R_OR1K_TLS_LE_HI16  
|______________|

After Link

----text--------
|               |
|  a = tp + off |
|_______________|

   tp + off
```


## Shared vs Static

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
GD - code will load MOD and OFF from GOT and call __tls_get_addr

LD - code will load a base with __tls_get_addr, then use local offsets for subsequent calls to load variable addresses.  Useful if more than one variables are accessed, it saves from having to do multiple calls to __tls_get_addr.

IE - code will load OFFSET got  and add to TP directly
  tp = TP
  tmp = load ie from GOT
  res = tmp + tp

LOCAL - code wil$l have OFFSET directly + TP directly - offset is determined as link time
  tmp = tls offset
  res = tp + tmp

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

- Android - https://android.googlesource.com/platform/bionic/+/HEAD/docs/elf-tls.md
- Oracle - https://docs.oracle.com/cd/E19683-01/817-3677/chapter8-20/index.html
- Drepper - https://www.akkadia.org/drepper/tls.pdf

