import assert from 'node:assert/strict';
import test from 'node:test';

import { compareImplementationRows } from './code-stats.mjs';

test('sorts implementation rows by layer, source LOC, then path', () => {
  const records = [
    { layer: 'frontends', variant: 'zeta', srcLoc: 10 },
    { layer: 'backends', variant: 'zeta', srcLoc: 20 },
    { layer: 'gateways', variant: 'proxy', srcLoc: 1 },
    { layer: 'backends', variant: 'alpha', srcLoc: 20 },
    { layer: 'backends', variant: 'small', srcLoc: 5 },
    { layer: 'frontends', variant: 'alpha', srcLoc: 10 },
  ];

  const ordered = [...records].sort(compareImplementationRows);

  assert.deepEqual(
    ordered.map(({ layer, variant, srcLoc }) => `${layer}/${variant}:${srcLoc}`),
    [
      'backends/small:5',
      'backends/alpha:20',
      'backends/zeta:20',
      'gateways/proxy:1',
      'frontends/alpha:10',
      'frontends/zeta:10',
    ],
  );
});
