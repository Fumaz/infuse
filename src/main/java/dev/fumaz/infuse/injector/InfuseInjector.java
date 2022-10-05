package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.Provider;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InfuseInjector implements Injector {

    private final Injector parent;
    private final List<Module> modules;

    public InfuseInjector(Injector parent, List<Module> modules) {
        this.parent = parent;
        this.modules = modules;
    }

    public void inject(Object object) {
        Set<Binding<?>> bindings = getBindings();

        for (Field field : object.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Inject.class)) {
                field.setAccessible(true);

                try {
                    field.set(object, provide(field.getType(), new Context<>(object.getClass(), this, ElementType.FIELD, field.getName(), field.getAnnotations())));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public <T> T provide(Class<T> type, Context<?> context) {
        Binding<T> binding = getBindingOrNull(type);

        if (binding != null) {
            return binding.getProvider().provide(context);
        }

        return construct(type, context);
    }

    @Override
    public <T> T construct(Class<T> type, Context<?> context) {
        Constructor<T> constructor = findInjectableConstructor(type);

        if (constructor == null) {
            throw new RuntimeException("No injectable constructor found for " + type.getName());
        }

        constructor.setAccessible(true);

        try {
            return constructor.newInstance(getConstructorArguments(constructor, context));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T construct(Class<T> type, Context<?> context, Object... args) {
        Constructor<T> constructor = findInjectableConstructor(type);

        if (constructor == null) {
            throw new RuntimeException("No injectable constructor found for " + type.getName());
        }

        constructor.setAccessible(true);

        try {
            return constructor.newInstance(getConstructorArguments(constructor, context, args));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return getBindingOrThrow(type).getProvider();
    }

    @Override
    public Injector getParent() {
        return parent;
    }

    @Override
    public Injector child(List<Module> modules) {
        return new InfuseInjector(this, modules);
    }

    @Override
    public List<Module> getModules() {
        List<Module> modules = new ArrayList<>();

        if (parent != null) {
            modules.addAll(parent.getModules());
        }

        modules.addAll(this.modules);

        return modules;
    }

    @Override
    public Set<Binding<?>> getBindings() {
        Set<Binding<?>> bindings = new HashSet<>();

        for (Module module : getModules()) {
            for (Binding<?> binding : module.getBindings()) {
                bindings.removeIf(binding::equals);
                bindings.add(binding);
            }
        }

        return bindings;
    }

    public <T> Binding<T> getBindingOrThrow(Class<T> type) {
        return (Binding<T>) getBindings().stream()
                .filter(binding -> binding.getType().isAssignableFrom(type) || type.isAssignableFrom(binding.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No binding found for type " + type));
    }

    public <T> Binding<T> getBindingOrNull(Class<T> type) {
        return (Binding<T>) getBindings().stream()
                .filter(binding -> binding.getType().isAssignableFrom(type) || type.isAssignableFrom(binding.getType()))
                .findFirst()
                .orElse(null);
    }

    private <T> Constructor<T> findInjectableConstructor(Class<T> type) {
        Constructor<T> injectableConstructor = null;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                if (injectableConstructor != null && injectableConstructor.isAnnotationPresent(Inject.class)) {
                    throw new IllegalArgumentException("Multiple injectable constructors found for type " + type);
                }

                injectableConstructor = (Constructor<T>) constructor;
            }

            if (constructor.getParameterCount() == 0 && injectableConstructor == null) {
                injectableConstructor = (Constructor<T>) constructor;
            }
        }

        return injectableConstructor;
    }

    private Object[] getConstructorArguments(Constructor<?> constructor, Object... provided) {
        Object[] args = new Object[constructor.getParameterCount()];

        for (int i = 0; i < args.length; i++) {
            Class<?> type = constructor.getParameterTypes()[i];
            Annotation[] annotations = constructor.getParameterAnnotations()[i];

            if (provided.length == 0 || provided.length <= i || constructor.getParameters()[i].isAnnotationPresent(Inject.class)) {
                args[i] = provide(type, new Context<>(constructor.getDeclaringClass(), this, ElementType.CONSTRUCTOR, constructor.getParameters()[i].getName(), annotations));
            } else {
                args[i] = provided[i];
            }
        }

        return args;
    }

}