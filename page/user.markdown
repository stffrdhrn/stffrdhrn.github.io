---
layout: default
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
{% highlight java %}
package net.shornepla.auth;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.userdetails.UserDetails;
/**
 * Model object for representing and User object used for authentication. This
 * object should be used for storing to the DB using hibernate.
 *
 * @author shorne
 * @since Mar 4, 2008
 */
public class User implements UserDetails {
    private static final long serialVersionUID = 4313286145927366498L;
    private int id;
    private String username;
    private String password;
    private GrantedAuthority[] authorities;
    private boolean enabled;
    public int getId() {
        return id;
    }
    public void setId(int id) {
        this.id = id;
    }
    public String getUsername() {
        return username;
    }
    public String getPassword() {
        return password;
    }
    public GrantedAuthority[] getAuthorities() {
        return authorities;
    }
    public boolean isEnabled() {
        return enabled;
    }
    public void setUsername(String username) {
        this.username = username;
    }
    public void setPassword(String password) {
        this.password = password;
    }
    public void setAuthorities(GrantedAuthority[] authorities) {
        this.authorities = authorities;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    /**
     * Not implemented, always return true
     */
    public boolean isAccountNonExpired() {
        return true;
    }
    /**
     * Not implemented, always return true
     */
    public boolean isAccountNonLocked() {
        return true;
    }
    /**
     * Not implemented, always return true
     */
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
{% endhighlight %}
