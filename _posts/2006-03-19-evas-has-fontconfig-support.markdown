---
layout: post
status: publish
published: true
title: Evas Has Fontconfig Support!
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 39
wordpress_url: /wordpress/?p=39
date: !binary |-
  MjAwNi0wMy0xOSAxMzowNTo0MiArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wMy0xOSAwNTowNTo0MiArMDkwMA==
categories:
- Tech Stories
tags: []
comments: []
---
<p>After my last post I was pretty clear on how evas font searching worked and how it could benefit from <a href="http://www.fontconfig.org" title="Font Config">FontConfig</a>.  So, I knuckled down and put the fontconfig code into evas.</p>
<p>After initial testing in E17 I was disappointed to find that edje text classes were broken. The problem was that whenever a reference to an edje would be given up (when removing a border) all of the text classes would be wiped out. I found a problem with this and seem to have fixed it. The latest changes are in CVS.</p>
<p>Now, E17 does not have to install anyfont. This is good because now we don't have to install and package CJK fonts, which was an issue we have been working on for a long time. Of course, the old methods of font loading are still working as always.</p>
<h2>My Font Setup</h2>
<pre>[shorne@Asus themes]$ enlightenment_remote -font-default-list
REPLY &lt;- BEGIN
REPLY: DEFAULT TEXT_CLASS="menu_item" NAME="Sans" SIZE=9
REPLY: DEFAULT TEXT_CLASS="move_text" NAME="monospace" SIZE=12
REPLY: DEFAULT TEXT_CLASS="resize_text" NAME="monospace" SIZE=12
REPLY: DEFAULT TEXT_CLASS="title_bar" NAME="Serif" SIZE=12
REPLY: DEFAULT TEXT_CLASS="default" NAME="Serif" SIZE=12
REPLY &lt;- END

[shorne@Asus themes]$ enlightenment_remote -font-fallback-list
REPLY &lt;- BEGIN
REPLY &lt;- END</pre>
<p><a href="/content/2006/03/FontConfig.png" class="imagelink" title="FontConfig"><img src="/content/2006/03/FontConfig.thumbnail.png" id="image38" alt="FontConfig" height="96" width="128" /></a><br />
Screenshot</p>
