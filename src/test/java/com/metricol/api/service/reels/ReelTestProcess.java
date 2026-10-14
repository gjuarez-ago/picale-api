package com.metricol.api.service.reels;

/** Proceso hijo deliberadamente simple para comprobar timeout y código de error. */
public final class ReelTestProcess {
    private ReelTestProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "fail".equals(args[0])) {
            System.err.println("fallo esperado");
            System.exit(7);
        }
        Thread.sleep(10_000);
    }
}
