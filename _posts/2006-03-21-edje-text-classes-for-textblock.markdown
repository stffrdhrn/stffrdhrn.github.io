---
layout: post
status: publish
published: true
title: Edje Text Classes for Textblock
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 40
wordpress_url: /wordpress/?p=40
date: !binary |-
  MjAwNi0wMy0yMSAyMjowMzoxMiArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wMy0yMSAxNDowMzoxMiArMDkwMA==
categories:
- Tech Stories
tags: []
comments: []
---
<p>Now that fontconfig is in and working well we need a way to configure fonts in Edje Textblocks.  Currently the fonts for text blocks are configured using the text block styles. Styles defined by a "base" style and may have multiple "tag" additions.  The following is a simple style.</p>
<pre>styles
{
style {
name: "about_style";
base: "font=Edje-Vera font_size=10 align=center";
tag:  "br" "n";
tag:  "hilight" "+ font=Edje-Vera-Bold";
}
}</pre>
<p>The style strings are sent to evas after being parsed and fixed by edje. In order to allow text classes to be used correctly we need a way to define text classes for both the base and tag styles seperately.</p>
<p>I am working on adding a new, edje specific, parameter  to the style string called "edje_text_style". The parameter will be parsed out before passing the style to the evas_textblock.</p>
<p>The text classes for textblocks will be shared with those for evas text objects. I must make sure that when text classes are updated changes are only made to the textblocks which use the changed class.</p>
