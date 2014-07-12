---
layout: page
status: publish
published: true
title: User
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 169
wordpress_url: http://blog.shorne-pla.net/?page_id=169
date: !binary |-
  MjAwOS0wMi0xMyAyMjowNjozNCArMDkwMA==
date_gmt: !binary |-
  MjAwOS0wMi0xMyAxNTowNjozNCArMDkwMA==
categories:
- Uncategorized
tags: []
comments: []
---
<p>[code lang="java"]<br />
package net.shornepla.auth;</p>
<p>import org.acegisecurity.GrantedAuthority;<br />
import org.acegisecurity.userdetails.UserDetails;</p>
<p>/**<br />
 * Model object for representing and User object used for authentication. This<br />
 * object should be used for storing to the DB using hibernate.<br />
 *<br />
 * @author shorne<br />
 * @since Mar 4, 2008<br />
 */<br />
public class User implements UserDetails {<br />
    private static final long serialVersionUID = 4313286145927366498L;</p>
<p>    private int id;<br />
    private String username;<br />
    private String password;<br />
    private GrantedAuthority[] authorities;<br />
    private boolean enabled;</p>
<p>    public int getId() {<br />
        return id;<br />
    }</p>
<p>    public void setId(int id) {<br />
        this.id = id;<br />
    }</p>
<p>    public String getUsername() {<br />
        return username;<br />
    }</p>
<p>    public String getPassword() {<br />
        return password;<br />
    }</p>
<p>    public GrantedAuthority[] getAuthorities() {<br />
        return authorities;<br />
    }</p>
<p>    public boolean isEnabled() {<br />
        return enabled;<br />
    }</p>
<p>    public void setUsername(String username) {<br />
        this.username = username;<br />
    }</p>
<p>    public void setPassword(String password) {<br />
        this.password = password;<br />
    }</p>
<p>    public void setAuthorities(GrantedAuthority[] authorities) {<br />
        this.authorities = authorities;<br />
    }</p>
<p>    public void setEnabled(boolean enabled) {<br />
        this.enabled = enabled;<br />
    }</p>
<p>    /**<br />
     * Not implemented, always return true<br />
     */<br />
    public boolean isAccountNonExpired() {<br />
        return true;<br />
    }</p>
<p>    /**<br />
     * Not implemented, always return true<br />
     */<br />
    public boolean isAccountNonLocked() {<br />
        return true;<br />
    }</p>
<p>    /**<br />
     * Not implemented, always return true<br />
     */<br />
    public boolean isCredentialsNonExpired() {<br />
        return true;<br />
    }<br />
}<br />
[/code]</p>
