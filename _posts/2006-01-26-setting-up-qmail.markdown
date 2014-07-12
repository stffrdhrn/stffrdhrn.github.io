---
layout: post
status: publish
published: true
title: Banking with qmail
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 8
wordpress_url: /wordpress/?p=8
date: !binary |-
  MjAwNi0wMS0yNiAxNzo1NTowNiArMDkwMA==
date_gmt: !binary |-
  MjAwNi0wMS0yNiAwOTo1NTowNiArMDkwMA==
categories:
- Life Stories
- Tech Stories
tags: []
comments: []
---
<p>Its the first day of my spring vacation and I figured I better get some things done.  Today, I was planning on going down to CitiBank and seeing about my options with the bank accounts here in China.  I have been looking for a single bank which offers the following:</p>
<ul>
<li>RMB Savings (Deposit RMB)</li>
<li>Credit Card</li>
<li>Able to remove USD (using current market exchange rate, no raping on the fees)</li>
<li>Accessable work wide</li>
</ul>
<p>This sounds reasonable, but most banks in china do not offer and RMB to foreign currency exchange.  This makes it difficult for me when I need to travel. I did some research online and found I could apply for an RMB Savings online. After applying I waited for a response and got none. I have decided to just go in tomorrow, in person.</p>
<p>Later in the day I set up qmail on my machine.  I need to set it up so I can get familiar with it while tracking down a bug at work. Final results:</p>
<ul>
<li>I have a new email address: shorne@shorne.homelinux.com</li>
<li>Mail is delivered straight to my laptop</li>
<li>My softhome.net mail is gathered by fetchmail then delivered locally using qmail</li>
<li>All mail is first dropped into my ~/Mailbox mbox queue (This is handy for reading mail remotely using the mail command)</li>
<li>I can use mail to quickly send mail from my new mail account</li>
<li>Evolution can pick up all the mail from the mbox queue</li>
</ul>
<p>The overall setup was easy, just had to create a .fetchmailrc .mailrc and compile and configure qmail. One tip: RTFM<br />
What a busy day</p>
