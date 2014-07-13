---
layout: page
status: publish
published: true
title: UserDetailProvider
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 162
wordpress_url: http://blog.shorne-pla.net/?page_id=162
date: !binary |-
  MjAwOS0wMi0xMyAyMjowNTo1MCArMDkwMA==
date_gmt: !binary |-
  MjAwOS0wMi0xMyAxNTowNTo1MCArMDkwMA==
categories:
- Uncategorized
tags: []
comments: []
---
<p>[code lang="java"]<br />
package net.shornepla.auth;</p>
<p>import org.acegisecurity.userdetails.UserDetails;<br />
import org.acegisecurity.userdetails.UserDetailsService;<br />
import org.acegisecurity.userdetails.UsernameNotFoundException;<br />
import org.hibernate.Session;<br />
import org.springframework.dao.DataAccessException;<br />
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;</p>
<p>/**<br />
 * Provides an UserDesailsService implementation based on Hibernate.<br />
 * This ties hibernate and acegi together.<br />
 *<br />
 * @author shorne<br />
 * @since Feb 13, 2009<br />
 */<br />
public class UserDetailProvider extends HibernateDaoSupport implements UserDetailsService {</p>
<p>    public UserDetails loadUserByUsername(String username)<br />
            throws UsernameNotFoundException, DataAccessException {</p>
<p>        Object object = null;</p>
<p>        Session session = this.getSession();<br />
        session.beginTransaction();</p>
<p>        object = session.createQuery("from User o where o.username=:username")<br />
                 .setString("username", username).uniqueResult();</p>
<p>        session.getTransaction().commit();</p>
<p>        if (object == null) {<br />
            throw new UsernameNotFoundException("User " + username + "was not found");<br />
        } else {<br />
            return (UserDetails) object;<br />
        }<br />
    }</p>
<p>}<br />
[/code]</p>
