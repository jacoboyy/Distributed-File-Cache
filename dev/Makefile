all: Proxy.class Server.class

%.class: %.java
	javac $<

.PHONY: format
format:
	# clang-format all the source code files
	find *.java *.cpp | sed 's| |\\ |g' | xargs clang-format -style=google -i

clean:
	rm -f *.class
