macnificent-plugin
==================

Marty Lamb mlamb@martiansoftware.com at [Martian Software, Inc.](http://martiansoftware.com)

macnificent-plugin is a Maven plugin for generating an up-to-date IEEE OUI data
file for use by the [macnificent](https://github.com/martylamb/macnificent) MAC
address library.

By default, the plugin makes sure it has the latest version of
http://standards.ieee.org/develop/regauth/oui/oui.txt (honoring HTTP
Last-Modified and ETag headers so as not to abuse IEEE servers),
caches the results in the local maven repository, and generates a
macnificent.dat project resource during Maven's generate-resources phase.

You'll likely also want to add [macnificent](https://github.com/martylamb/macnificent)
to your project as well.

Add the plugin repository to your project
-----------------------------------------

```xml
<project>
	...
    <pluginRepositories>
        <pluginRepository>
            <id>martiansoftware</id>
            <url>http://mvn.martiansoftware.com</url>
        </pluginRepository>
    </pluginRepositories> 
	...
</project>
```

Add the plugin to your build
----------------------------

```xml
<build>
	<plugins>
		<plugin>
			<groupId>com.martiansoftware</groupId>
			<artifactId>macnificent-plugin</artifactId>
			<version>0.1.0-SNAPSHOT</version>
			<executions>
				<execution>
					<goals>
						<goal>macnificent.dat</goal>
					</goals>				
				</execution>
			</executions>
		</plugin>
	</plugins>
</build>
```    

Some of the plugin's behavior is configurable via variables:

  * **macnificent.datfile** (default: macnificent.dat) - name of the
    generated resource file. 
  * **macnificent.url** (default: http://standards.ieee.org/develop/regauth/oui/oui.txt) -
    URL of the raw OUI data to download.
  * **macnificent.datdir** (default: target/generated-resources/macnificent-plugin) -
    directory where the data resource should be generated.  This is automatically
    added to the project as a resource location.
  * **macnificent.offline** (default: false) - can be used to disable the
    download entirely.  Build will fail if no data is cached.
    
    
