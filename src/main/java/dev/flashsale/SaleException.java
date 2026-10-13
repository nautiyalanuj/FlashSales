package dev.flashsale;

public class SaleException extends RuntimeException {
    final int status;
    public SaleException(int status, String message) { super(message); this.status = status; }
}
