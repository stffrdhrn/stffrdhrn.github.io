require "jekyll-import";

JekyllImport::Importers::WordPress.run({
      "dbname"   => "wordpress",
      "user"     => "grunt",
      "password" => "grunt1234",
      "host"     => "hpkan",
      "socket"   => "",
      "table_prefix"   => "shorne_",
      "clean_entities" => true,
      "comments"       => true,
      "categories"     => true,
      "tags"           => true,
      "more_excerpt"   => true,
      "more_anchor"    => true,
      "status"         => ["publish"]
});
