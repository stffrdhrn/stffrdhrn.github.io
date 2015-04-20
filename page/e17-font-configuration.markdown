---
layout: default
status: publish
published: true
title: E17 Font Configuration
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 50
wordpress_url: /wordpress/?page_id=50
date: !binary |-
  MjAwNi0wNC0yNCAwODozNTowMCArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wNC0yNCAwMDozNTowMCArMDkwMA==
categories:
- Uncategorized
tags: []
comments:
- id: 6
  author: Daniel Stonier
  author_email: stonierd@rit.kaist.ac.kr
  author_url: ''
  date: !binary |-
    MjAwNi0wNC0yNCAxMzoyMToxMSArMDkwMA==
  date_gmt: !binary |-
    MjAwNi0wNC0yNCAwNToyMToxMSArMDkwMA==
  content: ! "Thanks for the info! Managed to get fallbacks working in e17 without
    a hitch. You might want to note that the fonts.alias file is almost a copy of
    the fonts.dir file with the ttf filenames changed to whatever alias you wish.
    I chased my tail across the internet thinking there was something more to it than
    that. \r\n\r\nYou've also accidentally copy pasted steps 3 and 4 twice.\r\n\r\nI
    wonder if its worth us writing a complete from start to finish guide on setting
    up an asian language (fonts, e17 translation, input methods) in the get_e guide
    now that raster seems to have most of the details for internationalisation sorted."
- id: 7
  author: shorne
  author_email: shorne@softhome.net
  author_url: http://shorne.homelinux.com
  date: !binary |-
    MjAwNi0wNC0yNSAwMDoxMzozNyArMDkwMA==
  date_gmt: !binary |-
    MjAwNi0wNC0yNCAxNjoxMzozNyArMDkwMA==
  content: ! "Thanks, \r\nI got those items fixed. I think a complete guide would
    be good as well. Setup is not that hard. \r\n\r\nProbably the hardest thing is
    installing the input method. Setting up input methods in E is not too difficult."
---
<h2>What are font fallbacks?</h2>
<p>Fallbacks should be used if you do not want fontconfig fonts or you simply do not have fontconfig support. Currently enlightenment support 2 types of font searching and naming schemes. These are:</p>
<ul>
<li>Evas Native - This is similar to old xfs font directories, you can get a list of available font names using enlightenment_remote -font-available-list</li>
<li>Fontconfig - This is the new way to do things, you can get a list of available names using: fc-list</li>
</ul>
<p>In order to get font fallbacks working you will need to be using Evas Native fonts (Vera, Vera-Bold). Fontconfig fonts(Sans, Serif) do not need/support font fallbacks because they have fallbacks already built in.</p>
<h2>Installing Evas Fonts</h2>
<p><strong>Creating an Evas Font Directory</strong><br />
In order for evas to be able to use fonts you will need to create a directory which contains the following files: fonts.alias, fonts.dir. You can look at the default font directory for an example.  To create your own font directory you can do the following:</p>
<ul>
<li>1. make a new directory, or just use: ~/.e/e/fonts</li>
<li>2. Move/Copy some fonts into your new directory</li>
<pre>[shorne@asus fonts]$ ls
gbsn00lp.ttf  gkai00mp.ttf  kochi-gothic-subst.ttf  kochi-mincho-subst.ttf</pre>
<li>3. Create the fonts.scale and fonts.dir files</li>
<pre>$ mkfontscale
$ mkfontdir
[shorne@asus fonts]$ ls
fonts.dir    gbsn00lp.ttf  kochi-gothic-subst.ttf
fonts.scale  gkai00mp.ttf  kochi-mincho-subst.ttf</pre>
<li>4. From the fonts.dir make the fonts.alias file. The fonts.alias file is almost a copy of the fonts.dir file with the ttf filenames changed to whatever alias you wish.</li>
<pre>[shorne@asus fonts]$ cat fonts.alias
sungtil -arphic-ar pl sungtil gb-medium-r-normal--0-0-0-0-p-0-iso10646-1
kaitim -arphic-ar pl kaitim gb-medium-r-normal--0-0-0-0-p-0-iso10646-1
kochi-gothic gothic-medium-r-normal--0-0-0-0-p-0-iso10646-1
kochi-mincho mincho-medium-r-normal--0-0-0-0-p-0-iso10646-1</pre>
<li>5. Now your directory is setup you should now make sure enlightenment can find it.</li>
<pre>[shorne@asus fonts]$ enlightenment_remote -dirs-list fonts
REPLY <- BEGIN
REPLY Listing for "fonts"
REPLY: "/home/shorne/.e/e/fonts"
REPLY: "/home/shorne/local/share/enlightenment/data/fonts"
REPLY <- END
[shorne@asus fonts]$ enlightenment_remote -font-available-list
REPLY <- BEGIN
REPLY: "sungtil"
REPLY: "kaitim"
REPLY: "kochi-gothic"
REPLY: "kochi-mincho"
REPLY: "Vera-Bold-Italic"
REPLY: "Vera-Normal"
REPLY: "Vera-Bold"
REPLY: "Vera-Italic"
REPLY: "Vera-Mono-Bold-Italic"
REPLY: "Vera-Mono-Bold"
REPLY: "Vera-Mono-Italic"
REPLY: "Vera-Mono"
REPLY: "Vera-Serif"
REPLY: "Vera-Serif-Bold"
REPLY: "Vera"
REPLY <- END</pre>
</ul>
<p>The font dir should now be setup. The fonts available can now either be used as font fallbacks or as default fonts.</p>
<p><strong>Using Fallbacks</strong><br />
Once enlightenment can find your fallback fonts simply add them to the fallback list in the order you want. Example:</p>
<ul>
<li>kochi-mincho</li>
<li>sungtil</li>
</ul>
<p>This means that after evas/englightenment finds that the default font does not contain a specific character glyph we will fallback to kochi-mincho and then sungtil.</p>
