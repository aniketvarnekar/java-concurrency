/*
 * ServiceException — a checked exception used to demonstrate that checked exceptions
 * thrown inside Callable.call() are captured by the Future and rethrown as the cause
 * of ExecutionException when Future.get() is called.
 */
package examples.futuredemo;

class ServiceException extends Exception {
    ServiceException(String message) {
        super(message);
    }
}
