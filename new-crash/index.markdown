---
layout: page
status: publish
published: true
title: new crash
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 35
wordpress_url: /wordpress/?page_id=35
date: !binary |-
  MjAwNi0wMy0wOCAyMTo1MzozMiArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wMy0wOCAxMzo1MzozMiArMDkwMA==
categories:
- Uncategorized
tags: []
comments: []
---
<pre>Program received signal SIGSEGV, Segmentation fault.
0x4a64004c in _int_free () from /lib/tls/libc.so.6
(gdb) bt
#0  0x4a64004c in _int_free () from /lib/tls/libc.so.6
#1  0x4a63f01b in free () from /lib/tls/libc.so.6
#2  0x4a83da18 in _XcursorCreateFontCursor ()
from /usr/X11R6/lib/libXcursor.so.1
#3  0x4a74738d in XCloseDisplay () from /usr/X11R6/lib/libX11.so.6
#4  0xb7fbe96d in _ecore_x_shutdown (close_display=1) at ecore_x.c:412
#5  0xb7fbea11 in ecore_x_shutdown () at ecore_x.c:442
#6  0x08062977 in _e_main_x_shutdown () at e_main.c:777
#7  0x0806290e in _e_main_shutdown (errorcode=0) at e_main.c:766
#8  0x0806182d in main (argc=1, argv=0xbffff7f4) at e_main.c:690</pre>
