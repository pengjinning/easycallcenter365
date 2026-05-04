#!/bin/bash

cmdPath="/usr/bin/"
my_user="root"
my_password="easycallcenter365"
bakDir="/home/dbbakup/"
bakDbName="easycallcenter365"
fileExtension="_bakup.sql.gz"

docker exec -it mysql /usr/bin/mysqlpump -u$my_user -p$my_password -h127.0.0.1 --port 3306  --defer-table-indexes --single-transaction --default-character-set=utf8   --default-parallelism=5 -B $bakDbName | gzip >  $bakDir/eccbakup_"$(date +'%Y-%m-%d')"$fileExtension

find $bakDir/ -type f -ctime +15 -exec rm {} \;
