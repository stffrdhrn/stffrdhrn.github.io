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
{% highlight java %}
package net.shornepla.auth;
import org.acegisecurity.GrantedAuthority;
/**
 * Model class for representing an authority used for Acegi. Persist this
 * in the DB using hibernate.
 *
 * @author shorne
 * @since Mar 4, 2008
 */
public class Authority implements GrantedAuthority{
    private static final long serialVersionUID = -7492160131476139271L;
    private int id;
    private int idx;
    private String authority;
    public int getId() {
        return id;
    }
    public void setId(int id) {
        this.id = id;
    }
    public String getAuthority() {
        return authority;
    }
    public void setAuthority(String authority) {
        this.authority = authority;
    }
    public int getIdx() {
        return idx;
    }
    public void setIdx(int idx) {
        this.idx = idx;
    }
}
{% endhighlight %}
