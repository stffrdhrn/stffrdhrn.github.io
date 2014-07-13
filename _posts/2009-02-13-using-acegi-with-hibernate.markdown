---
layout: post
status: publish
published: true
title: Using Acegi with Hibernate
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 144
wordpress_url: http://blog.shorne-pla.net/?p=144
date: !binary |-
  MjAwOS0wMi0xMyAyMjoxNjowMiArMDkwMA==
date_gmt: !binary |-
  MjAwOS0wMi0xMyAxNToxNjowMiArMDkwMA==
categories:
- Tech Stories
tags: []
comments:
- id: 13292
  author: newbie
  author_email: nyuby@rocketmail.com
  author_url: ''
  date: !binary |-
    MjAwOS0wNS0yMyAxODoyMDo1MSArMDkwMA==
  date_gmt: !binary |-
    MjAwOS0wNS0yMyAxMToyMDo1MSArMDkwMA==
  content: ! "I have learn with your example, but i don't know what wrong with my
    mistake. I get the error like this:\r\n\r\norg.springframework.beans.factory.BeanCreationException:
    Error creating bean with name 'userDetailProvider' defined in ServletContext resource
    [/WEB-INF/applicationContext.xml]: Error setting property values; nested exception
    is org.springframework.beans.NotWritablePropertyException: Invalid property 'sessionFactory'
    of bean class [tes.auth.Authority]: Bean property 'sessionFactory' is not writable
    or has an invalid setter method. Does the parameter type of the setter match the
    return type of the getter?\r\n        at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.applyPropertyValues(AbstractAutowireCapableBeanFactory.java:1303)\r\n
    \       at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.populateBean(AbstractAutowireCapableBeanFactory.java:1042)\r\n\r\ni
    try many example, but nothing success yet, i'm sory.. i'm very newbies..\r\ncould
    you help me ..?\r\nThanks before..."
- id: 13325
  author: shorne
  author_email: shorne@gmail.com
  author_url: http://blog.shorne-pla.net
  date: !binary |-
    MjAwOS0wNS0yNiAwNToxMjo0MyArMDkwMA==
  date_gmt: !binary |-
    MjAwOS0wNS0yNSAyMjoxMjo0MyArMDkwMA==
  content: ! "Are you sure your UserDetailProvider implmentation is extending HibernateDaoSupport?\r\n\r\nThe
    sessionFactory property that your error is complaining about is exposed by springs
    HibernateDaoSupport class."
- id: 13762
  author: MilosI
  author_email: sosabi82@yahoo.com
  author_url: ''
  date: !binary |-
    MjAwOS0wNy0xNyAyMToxMjoyNiArMDkwMA==
  date_gmt: !binary |-
    MjAwOS0wNy0xNyAxNDoxMjoyNiArMDkwMA==
  content: Thanks man! Great work
---
<p>
A while back I started working on a web application with, at the time, all new java technologies. Once the web application needed an authentication framework I turned to <a href="http://www.acegisecurity.org/">acegi</a> (now part of Spring Security API). Acegi security provides much of the authentication features a developer requires in a web application including: remember me, failed login handling, public content access and so on.  Other technologies I used where <a href="http://struts.apache.org/2.x/">Struts2</a>, <a href="http://www.springsource.org/">Spring</a> and <a href="http://www.hibernate.org/">Hibernate</a>.</p>
<p>
Since I was using hibernate and spring <a href="http://static.springsource.org/spring/docs/2.5.1/api/org/springframework/orm/hibernate3/support/HibernateDaoSupport.html">daos</a> I thought it best that I store my user names and passwords in the database via the same mechanism.  That is, I needed to use Acegi for authentication and Hibernate and Spring for managing the user detail persistence layer.  After searching a few forums it turned out that many people wanted to do the same, but no one was providing a solution.  Proceeding with a brief brainstorm session and research into the acegi API I came up with my own UserDetailsService implementation backed by hibernate and spring.  Its simple but it provides me with what I need and I hope it will be a helpful reference for others as well.</p>
<h3>Source Code</h3>
<p>The code used for the implementation is packaged as <a href="http://www.shorne-pla.net/uploads/auth.jar">auth.jar</a> with class and source files for your reference. Please do with it as you like (BSD license). The contents of the archive are described below:</p>
<h4>net.shornepla.auth</h4>
<ul>
<li><a href="/page/authority.html">Authority.java</a> - a userw authority (i.e. USER_ROLE)  Implements <a href="http://www.acegisecurity.org/acegi-security/apidocs/org/acegisecurity/userdetails/UserDetails.html">UserDetails</a></li>
<li><a href="/page/user.html">User.java</a> - a user, having user name and password. Implements <a href="http://www.acegisecurity.org/acegi-security/apidocs/org/acegisecurity/GrantedAuthority.html">GrantedAuthority</a></li>
<li><a href="/page/userdetailprovider.html">UserDetailProvider.java</a> - Uses hibernate to provide the user details service. Implements <a href="http://www.acegisecurity.org/acegi-security/apidocs/org/acegisecurity/userdetails/UserDetailsService.html">UserDetailsService</a></li>
<li><a href="http://www.shorne-pla.net/uploads/model.hbm.xml">model.hbm.xml</a> - Hibernate model description for User and Authority</li>
</ul>
<p>Together these small classes provide the groundwork for our authentication layer.  Next, the hard part is dealing with all of the Acegi spring configuration.</p>
<h3>ApplicationContext.xml</h3>
<p>Acegi is loaded via two spring application context xml files.  This first one is pretty basic, first it initialises my hibernate authentication implementation.  Next it initialises the authentication provider.  </p>

{% highlight xml %}
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN//EN" "http://www.springframework.org/dtd/spring-beans.dtd">
<beans default-autowire="autodetect">
  <!-- Load the hibernate model for authentication -->
  <bean id="sessionFactory" 
    class="org.springframework.orm.hibernate3.LocalSessionFactoryBean" >
    <property name="mappingResources">
      <list>
	<value>net/shornepla/auth/model.hbm.xml</value>
      </list>
    </property>
  </bean>
  <!-- The hibernate backed implementation for UserDetailService -->
  <bean  id="userDetailProvider"
    class="net.shornepla.auth.UserDetailProvider" >
    <property name="sessionFactory" ref="sessionFactory" />
  </bean>
  <!-- Just use MD5 password hashing -->
  <bean id="passwordEncoder"
    class="org.acegisecurity.providers.encoding.Md5PasswordEncoder" />
  <!-- Tie together with the DaoAuthenticationProvider -->
  <bean id="daoAuthenticationProvider"
    class="org.acegisecurity.providers.dao.DaoAuthenticationProvider" >
    <property name="userDetailsService">
       <ref local="userDetailProvider"/>
    </property>
    <property name="passwordEncoder">
      <ref local="passwordEncoder"/>
    </property>
  </bean>
</beans>
{% endhighlight %}

<h3>applicationContext-acegi-security.xml</h3>
<p>
The second application context config is <a href="http://www.shorne-pla.net/uploads/applicationContext-acegi-security.xml">applicationContext-acegi-security.xml</a>.  This is mostly copied directly out of the acegi example and simplified as much as possible.</p>
<p>
The main beans here are:</p>
<ul>
<li>authenticationManager - uses the above defined <b>daoAuthenticationProvider</b></li>
<li>filterInvocationInterceptor - specifies which roles have access to what</li>
<li>authenticationProcessingFilter - specifies which pages are used for authentication</li>
</ul>
<p>
All together, these resources will probably not work for you as they require a web application to be deployed.  However, the pieces I provide should make integration into your application as simple as possible. If there are any issues or suggestions please let me know.</p>
