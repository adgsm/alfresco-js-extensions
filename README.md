# Alfresco AMP containing set of javascript extensions

Set of extensions to be used with Alfresco javascript API

### Features
* JSZip (zip provisional container and its content alltogether with apertain metadata)
* JSUnzip (unzip content zipped with JSZip)
* Request (make GET,PUT,POST,DELETE requestd from javascript API)
* JSBase64 (Base64 content encoding/decoding from javascript API)
* Category (import/export Alfresco categories from javascript API)
* ...

### Usage

#### Create AMP
```
mvn clean install
```
#### Install AMP
```
/opt/alfresco/bin/apply_amps.sh
```
or
```
java -jar /opt/alfresco/bin/alfresco-mmt.jar install alfresco-js-extensions /opt/alfresco/tomcat/webapps/alfresco.war
```

### License
Licensed under the MIT license.
http://www.opensource.org/licenses/mit-license.php
