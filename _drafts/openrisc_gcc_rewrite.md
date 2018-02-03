---
title: OpenRISC GCC rewrite
layout: post
date: 2018-02-03
categories: [ software, embedded, openrisc ]
---

*I am working on a OpenRISC GCC port rewrite, here's why.*

For the past few years I have been working as a contributor to the
[OpenRISC](http://openrisc.io) CPU project.  My work has mainly been focused on
developing interest in the project by keeping the toolchains and software
maintained and pushing outstanding [patches upstream](https://en.wikipedia.org/wiki/Upstream_(software_development).

I have made way getting [Linux SMP](https://www.phoronix.com/scan.php?page=news_item&px=OpenRISC-SMP-Linux-V3) support, 
the [GDB port](https://www.gnu.org/software/gdb/download/ANNOUNCEMENT), QEMU fixes and other
patches written, reviewed and committed to the upstream repositories.

However there is one project that has been an issue from the beginning; **GCC**.
OpenRISC has a mature [GCC](https://gcc.gnu.org) port started in early 2000s.
The issue is it is not upstream due to one early contributor not having signed
over his copyright.  I decided to start with the rewrite. To do this I will:

  - Write a [SMH](https://github.com/stffrdhrn/binutils-gdb/tree/smh-port) dummy
    architecture port following the [ggx porting guide](http://atgreen.github.io/ggx/) (moxie) guide.
  - Use that basic knowledge to start on the or1k port.

If you are interested please reach out on IRC or E-mail.
