/*
 * AccountRegistry — synchronized static method.
 *
 * The monitor for a synchronized static method is the Class object
 * (AccountRegistry.class), not any instance. There is one Class object per
 * class per classloader, so this lock is shared across all threads in the
 * same classloader context. A synchronized static method and a synchronized
 * instance method on the same class use different monitors and do not
 * contend with each other.
 */
package examples.synchronizeddemo;

class AccountRegistry {

    private static int totalAccounts = 0;

    public static synchronized void register() {
        totalAccounts++;
    }

    public static synchronized int total() {
        return totalAccounts;
    }
}
