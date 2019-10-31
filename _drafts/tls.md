
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
| a []     |
|          |
-----------|

After Link

 ---text----   ---got---       --rela------
|           | |         |     |            |
|  []       | | mod [ ] |<----| TLS_DTPMOD |
|___________| | off [ ] |<----| TLS_DTPOFF |
              |_________|     |____________|
                      |
                      ==============|-----|
                                    V     V
                     __tls_get_addr(mod, off)

```

Local Dynamic

  

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
