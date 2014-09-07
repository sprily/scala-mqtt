credentials ++= (for {
  username <- Option(System.getenv().get("NEXUS_USER"))
  password <- Option(System.getenv().get("NEXUS_PASSWORD"))
  credentials = Credentials("Sonatype Nexus Repository Manager", 
                            "repo.sprily.co.uk",
                            username, 
                            password)
} yield credentials).toSeq
