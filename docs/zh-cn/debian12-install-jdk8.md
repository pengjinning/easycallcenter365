# debian12下安装jdk8

jdk-8u391-linux-x64-debian12.tar.gz下载完成后，使用以下命令解压缩JDK安装文件：

```sh
tar -zxvf  jdk-8u391-linux-x64.tar.gz -C /usr/local
```
 
接下来，您需要编辑.bashrc文件，以设置JAVA_HOME和其他环境变量。使用以下命令打开.bashrc文件：

```sh
vi ~/.bashrc
```
 
在.bashrc文件中，在文件末尾处，添加以下行来设置JAVA_HOME、JRE_HOME、CLASSPATH和PATH变量：
```txt
export JAVA_HOME=/usr/local/jdk1.8.0_391
export JRE_HOME=${JAVA_HOME}/jre
export CLASSPATH=.:${JAVA_HOME}/lib:${JRE_HOME}/lib
export PATH=${JAVA_HOME}/bin:$PATH
```

保存并关闭.bashrc文件。

运行以下命令使更新的.bashrc文件生效：

```sh
source ~/.bashrc
```