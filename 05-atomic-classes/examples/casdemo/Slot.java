/*
 * Slot — a simple named value used to make the ABA problem visible in output.
 *
 * AtomicReference compares references with ==, not .equals(). Two distinct Slot
 * instances with the same name are different references; only the exact same object
 * satisfies a CAS match. Naming each instance ("A", "B", "C") makes it clear which
 * object identity is in play at each step of the ABA demonstration.
 */
package examples.casdemo;

class Slot {
    final String name;

    Slot(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
