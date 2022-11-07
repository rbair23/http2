Simple Java HTTP 1.1 & HTTP 2.0 server with no dependencies. Has aim to be low object garbage creation as much as possible.

Big thanks to h2spec for a comprehensive and easy to understand set of tests for http2!

We have very minimal HTTP 1.0 support, to allow testing with apache `ab` command, maybe in the future this will be removed. For HTTP 2.0 we support both the pre-amble and upgrade header methods.