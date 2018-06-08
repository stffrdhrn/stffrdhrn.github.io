---
title: OpenRISC GCC rewrite
layout: post
date: 2018-02-03 13:44
categories: [ software, embedded, openrisc ]
---

*I am working on an OpenRISC GCC port rewrite, here's why.*

For the past few years I have been working as a contributor to the
[OpenRISC](http://openrisc.io) CPU project.  My work has mainly been focused on
developing interest in the project by keeping the toolchains and software
maintained and pushing outstanding [patches upstream](https://en.wikipedia.org/wiki/Upstream_%28software_development%29).

I have made way getting [Linux SMP](https://www.phoronix.com/scan.php?page=news_item&px=OpenRISC-SMP-Linux-V3) support,
the [GDB port](https://www.gnu.org/software/gdb/download/ANNOUNCEMENT), QEMU fixes and other
patches written, reviewed and committed to the upstream repositories.

However there is one project that has been an issue from the beginning; **GCC**.
OpenRISC has a mature [GCC](https://gcc.gnu.org) port started in early 2000s.
The issue is it is not upstream due to one early contributor not having signed
over his copyright.  I decided to start with the rewrite. To do this I will:

  - Write a [SMH](https://github.com/stffrdhrn/binutils-gdb/tree/smh-port) dummy
    architecture port following the [ggx porting guide](http://atgreen.github.io/ggx/) (moxie) guide.
  - Use that basic knowledge to start on the [or1k port](https://github.com/stffrdhrn/gcc/tree/or1k-port).

If you are interested please reach out on IRC or E-mail.

## Updates

See my articles on the progress of the project.

 - [OpenRISC GCC Status Update]({% post_url 2018-05-16-openrisc_gcc_status_update %}) - Announcement on Hello World working
 - [GCC Important Passes]({% post_url 2018-06-03-gcc_passes %}) - My guide to understanding the passes
 - [GCC Stack Frame]({% post_url 2018-06-08-gcc_stack_frames %}) - My guide to understanding the stack frame

## Further Reading

 - [Writing a GCC back end](https://kristerw.blogspot.com/2017/08/writing-gcc-backend_4.html) - Krister Walfridsson’s excellent new series
 - [GCC Internals](https://gcc.gnu.org/onlinedocs/gccint/) - The GNU reference manual
 - [Using GCC](https://gcc.gnu.org/onlinedocs/gcc/) - The GCC user manual, some parts are for the backend
   - [Constraints for asm](https://gcc.gnu.org/onlinedocs/gcc/Constraints.html#Constraints) - Constraints sub section
   - [Spec files](https://gcc.gnu.org/onlinedocs/gcc/Spec-Files.html#Spec-Files) - Details on the spec `%{}` language used by the GCC driver
