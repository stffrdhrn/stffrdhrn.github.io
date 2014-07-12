---
layout: post
status: publish
published: true
title: Text Classes for Edje Textblock - Working
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 42
wordpress_url: /wordpress/?p=42
date: !binary |-
  MjAwNi0wMy0yNSAxMjoyMjo1NCArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wMy0yNSAwNDoyMjo1NCArMDkwMA==
categories:
- Tech Stories
tags: []
comments: []
---
<p>While edev CVS has been down I got the edje textblock to accept text_classes in the style tags.  This is needed so that textblocks can use fontconfig font famlies. This also allows us to configure textblock fonts on the fly.</p>
<p>This code right now is solid but it could use a touch of optimization. Currently the styles are recalculated whenever an edje is loaded. This is a problem because the edjes are loaded all of the time. I hope raster can give me some ideas on this once CVS comes back online.</p>
<h2>Screenshot</h2>
<p>The screenshot says it all.</p>
<p><a href="/wp-content/uploads/2006/03/fontconfig_tb.png" class="imagelink" title="fontconfig_tb.png"><img src="/wp-content/uploads/2006/03/fontconfig_tb.thumbnail.png" id="image41" alt="fontconfig_tb.png" height="96" width="128" /></a></p>
