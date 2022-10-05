package dev.fumaz.infuse.module;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingBuilder;

import java.util.HashSet;
import java.util.Set;

public abstract class InfuseModule implements Module {

    private final Set<Binding<?>> bindings = new HashSet<>();

    @Override
    public Set<Binding<?>> getBindings() {
        return bindings;
    }

    public <T> BindingBuilder<T> bind(Class<T> type) {
        return new BindingBuilder<>(type, bindings);
    }

}
