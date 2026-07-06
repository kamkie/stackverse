export type PropFunction<T extends (...args: any[]) => any> = T;

type PublicProps<P> = P & { children?: any; key?: any };

export declare function $<T extends (...args: any[]) => any>(fn: T): T;
export declare function component$<P>(
  component: (props: P) => any,
): (props: PublicProps<P>) => any;
export declare function useStore<T extends object>(value: T): T;
export declare function useSignal<T>(value?: T): { value: T };
export declare function useVisibleTask$(
  task: (ctx: {
    cleanup: (callback: () => void) => void;
    track: <T>(expression: () => T) => T;
  }) => void | Promise<void>,
): void;
export interface RenderOptions {
  [key: string]: unknown;
}

export declare function render(container: Element | Document, jsx: any, options?: RenderOptions): Promise<void>;
export declare function Slot(): any;
