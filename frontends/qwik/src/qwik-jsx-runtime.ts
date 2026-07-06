export namespace JSX {
  export interface Element {}
  export interface ElementChildrenAttribute {
    children: {};
  }
  export interface IntrinsicElements {
    [elementName: string]: any;
  }
}

export declare const Fragment: any;
export declare function jsx(type: any, props: any, key?: any): any;
export declare function jsxs(type: any, props: any, key?: any): any;
export declare function jsxDEV(type: any, props: any, key?: any): any;
