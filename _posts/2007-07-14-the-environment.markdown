---
layout: post
status: publish
published: true
title: The Environment
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 77
wordpress_url: http://blog.shorne-pla.net/?p=77
date: !binary |-
  MjAwNy0wNy0xNCAwNzoyMTowNSArMDkwMA==
date_gmt: !binary |-
  MjAwNy0wNy0xNCAwMDoyMTowNSArMDkwMA==
categories:
- Tech Stories
tags: []
comments: []
---
<p>There has been something bothering me on my desktop for a while. It seems that whatever I try to do LD_LIBRARY_PATH can not be set when starting a session via <a href="http://www.gnome.org/projects/gdm/" title="Gnome Display Manager">gdm</a>. I used to think that is was just because my .bash_profile was not getting loaded, but it was, my other variables were set fine PATH, LDFLAGS, CFLAGS, etc.</p>
<p>I used to get around this by writting small scripts such as:</p>
<pre>
#!/bin/bash

LD_LIBRARY_PATH="/usr/lib64/firefox-1.5.0.10:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH
pushd /opt/shorne/xine/bin
exec ./gxine "$*"

popd
</pre>
<p>But today, I had some free time and a lightbulb went dead.  I needed to figure it out.  I did a few google searchs and came up with this gem "<a href="http://antonym.org/node/113" title="SSH LD_LIBRARY_PATH"><tt>ssh-agent</tt> is smart enough to detect that <tt>LD_LIBRARY_PATH</tt> is a potential security hole, and is thus not to be trusted</a>".</p>
<p>So, I went into my /etc/X11/xinit/xinitrc-common and cleared SSH_AGENT.  After logging in everything works fine. Finally.</p>
