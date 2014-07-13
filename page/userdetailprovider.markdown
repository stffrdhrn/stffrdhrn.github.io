---
layout: default
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
{% highlight java %}
package net.shornepla.auth;
import org.acegisecurity.userdetails.UserDetails;
import org.acegisecurity.userdetails.UserDetailsService;
import org.acegisecurity.userdetails.UsernameNotFoundException;
import org.hibernate.Session;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;
/**
 * Provides an UserDesailsService implementation based on Hibernate.
 * This ties hibernate and acegi together.
 *
 * @author shorne
 * @since Feb 13, 2009
 */
public class UserDetailProvider extends HibernateDaoSupport implements UserDetailsService {
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException, DataAccessException {
        Object object = null;
        Session session = this.getSession();
        session.beginTransaction();
        object = session.createQuery("from User o where o.username=:username")
                 .setString("username", username).uniqueResult();
        session.getTransaction().commit();
        if (object == null) {
            throw new UsernameNotFoundException("User " + username + "was not found");
        } else {
            return (UserDetails) object;
        }
    }
}
{% endhighlight %}
