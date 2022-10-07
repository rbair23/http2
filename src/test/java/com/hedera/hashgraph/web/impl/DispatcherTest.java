package com.hedera.hashgraph.web.impl;

/*
 * Attack Vectors:
 *   - Open a bunch of connections but don't send data, or just send pings
 *      - Make sure TCP_TIMEOUT is set to a reasonable value
 *      - Have a reasonable configuration for max amount of time a connection can be open
 *          - Also covers the case where data is dribbling in slowly
 *      - Find the right value for the size of backlog of connections
 *      - Close an idle connection that isn't sending data
 *   - A connection sends too many header values (too much header data)
 *      - Detect too much header data and close with error
 *   - A connection wants to send more data than it should
 *      - Detect and close with error
 *   - A connection is valid but sends garbage header data
 *      - We won't detect the garbage header data until we go to parse it, which happens later.
 *        So we're just open to this kind of attack.
 *   - Somebody sends completely random garbage data for the header. Can they exploit us?
 *      - The parser has to be careful about things like max-key-size, max-value-size, and
 *        throw exceptions on any parse errors.
 *      - No code execution (sql injection, etc.) is possible at this phase
 *      - Mitigate with fuzz testing
 *
 */
public class DispatcherTest {
}
