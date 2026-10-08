package com.sysadminanywhere.m3.messaging.worker;
/** Checked on the executing thread immediately before transport side effects. */
public final class ExecutionLease {
    private static final ThreadLocal<WorkerSlotService> CURRENT=new ThreadLocal<>();
    private ExecutionLease(){}
    static void enter(WorkerSlotService slot){CURRENT.set(slot);}
    static void leave(){CURRENT.remove();}
    public static void assertOwned(){var slot=CURRENT.get();if(slot!=null)slot.assertOwned();}
    public static String token(){var slot=CURRENT.get();return slot==null?null:slot.token();}
}
