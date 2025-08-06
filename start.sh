#!/bin/bash
echo "The service is being launched..."

echo "This will probably take 60 to 80 seconds. "

echo "Try to start mysql-server..."
docker start  mysql

echo "Try to start funasr..."
docker start  funasr-online-2710
sleep 25

echo "Try to start freeswitch..."
/usr/bin/sh /usr/local/freeswitchvideo/bin/freeswitch_start.sh
# docker start  freeswitch-debian12

sleep 15

echo "Try to start easycallcenter365.jar "
/usr/bin/nohup /usr/local/jdk1.8.0_391/bin/java  -Dfile.encoding=UTF-8  -jar  /home/call-center/easycallcenter365.jar   --spring.profiles.active=uat   > /dev/null 2>&1 &
sleep 15

echo "Try to start easycallcenter365-gui.jar"
/usr/bin/nohup /usr/local/jdk1.8.0_391/bin/java  -Dfile.encoding=UTF-8  -jar  /home/call-center/easycallcenter365-gui.jar   --spring.profiles.active=test  > /dev/null 2>&1 &
sleep 15

echo " "
echo "Done."
echo " "