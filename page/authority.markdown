---
layout: default
status: publish
published: true
title: Authority
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 171
wordpress_url: http://blog.shorne-pla.net/?page_id=171
date: !binary |-
  MjAwOS0wMi0xMyAyMjowNzozMSArMDkwMA==
date_gmt: !binary |-
  MjAwOS0wMi0xMyAxNTowNzozMSArMDkwMA==
categories:
- Uncategorized
tags: []
comments: []
---
<p>[code lang="java"]<br />
package net.shornepla.auth;</p>
<p>import org.acegisecurity.GrantedAuthority;</p>
<p>/**<br />
 * Model class for representing an authority used for Acegi. Persist this<br />
 * in the DB using hibernate.<br />
 *<br />
 * @author shorne<br />
 * @since Mar 4, 2008<br />
 */<br />
public class Authority implements GrantedAuthority{<br />
    private static final long serialVersionUID = -7492160131476139271L;</p>
<p>    private int id;<br />
    private int idx;<br />
    private String authority;</p>
<p>    public int getId() {<br />
        return id;<br />
    }</p>
<p>    public void setId(int id) {<br />
        this.id = id;<br />
    }</p>
<p>    public String getAuthority() {<br />
        return authority;<br />
    }</p>
<p>    public void setAuthority(String authority) {<br />
        this.authority = authority;<br />
    }</p>
<p>    public int getIdx() {<br />
        return idx;<br />
    }</p>
<p>    public void setIdx(int idx) {<br />
        this.idx = idx;<br />
    }<br />
}<br />
[/code]</p>
