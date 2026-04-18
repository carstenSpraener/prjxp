package de.spraener.prjxp.common.util;

public class ValueContainer<T> {
    private T value;

    public ValueContainer(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public ValueContainer<T> setValue(T value) {
        this.value = value;
        return this;
    }

    public String toString() {
        return "" + value;
    }
}
