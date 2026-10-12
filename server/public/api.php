<?php
declare(strict_types=1);
// Hostinger account-private storage, never beneath the public web root.
$private=getenv('REDWOOD_PRIVATE_DIR') ?: dirname(__DIR__,4).'/redwood-arrows';
require $private.'/app.php';
redwood_http($private);
