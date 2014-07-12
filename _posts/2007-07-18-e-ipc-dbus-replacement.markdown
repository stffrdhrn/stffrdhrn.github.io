---
layout: post
status: publish
published: true
title: E IPC - Dbus Replacement
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 85
wordpress_url: http://blog.shorne-pla.net/?p=85
date: !binary |-
  MjAwNy0wNy0xOCAyMDo0OToyOCArMDkwMA==
date_gmt: !binary |-
  MjAwNy0wNy0xOCAxMzo0OToyOCArMDkwMA==
categories:
- Tech Stories
tags: []
comments:
- id: 128
  author: Rizzi
  author_email: onein@inbox.com
  author_url: ''
  date: !binary |-
    MjAwNy0wOC0xNyAwNzo1NzoxMSArMDkwMA==
  date_gmt: !binary |-
    MjAwNy0wOC0xNyAwMDo1NzoxMSArMDkwMA==
  content: ! "hi shorne,I begun a small blog in Spanish about E17,can u speak here
    a little more about this topic? and do you believe that it should give it in my
    blog? of course with a link to yours and in spanish,if u donÂ´t mind.thanks!\r\nsorry
    for my english am Italian and I love the Spanish language."
- id: 653
  author: cagpearia
  author_email: heistiterce@bk.ru
  author_url: ''
  date: !binary |-
    MjAwOC0wNS0xNiAwMjo1NDoxMiArMDkwMA==
  date_gmt: !binary |-
    MjAwOC0wNS0xNSAxOTo1NDoxMiArMDkwMA==
  content: ! "Hello my friends :) \r\n;)"
---
<p>I have been talking to <a href="http://www.rasterman.com">raster</a> for some time about replacing the e17 ipc mess with dbus.  A while back it just didnt seem like it be feasable for the e17 release. But now with e_dbus looking good and e17's file manager dependencies on hal it looks like it might be our ticket to cleaning up the enlightenment remote interface.  The basic Idea is to provide very basic ipc functionality build into E (load,remove,list modules).  Other ipc functionality should be extensible through modules.</p>
<p>I hacked on this concept a bit, basically to relearn most of the dbus stuff, I ended  up with a module which advertises some e17 interfaces on dbus.</p>
<p>For now I call it taxi.</p>
<p><a href="/wp-content/uploads/2007/07/dbus-01.png" title="Screener"><img src="/wp-content/uploads/2007/07/dbus-01.thumbnail.png" alt="Screener" /></a></p>
