all:
	javac myftpserver.java
	javac myftp.java

server:
	java myftpserver 5000 4570

client:
	java myftp 127.0.0.1 5000 4570

clean:
	$(RM) *.class
