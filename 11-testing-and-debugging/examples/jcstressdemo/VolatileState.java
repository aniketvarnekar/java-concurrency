/*
 * VolatileState — shared state for the volatile visibility stress test.
 *
 * actor1 (writer) writes data then sets the volatile flag.
 * actor2 (reader) reads the volatile flag first, then reads data.
 *
 * If volatile provides the happens-before guarantee the JMM promises,
 * actor2 must never observe flag=true while data is still 0.
 * That combination is the FORBIDDEN outcome.
 */
package examples.jcstressdemo;

class VolatileState {
    int           data = 0;
    volatile boolean flag = false;
}
