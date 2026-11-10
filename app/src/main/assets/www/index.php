<?php
echo "<h2>Pocket Server is running</h2>";
echo "<p>PHP version: " . phpversion() . "</p>";

if (function_exists("mysqli_connect")) {
    $link = @mysqli_connect("127.0.0.1", "root", "", "", 3307);
    if ($link) {
        $res = mysqli_query($link, "SELECT VERSION() AS v");
        $row = mysqli_fetch_assoc($res);
        echo "<p>MariaDB version: " . htmlspecialchars($row["v"]) . "</p>";
        mysqli_close($link);
    } else {
        echo "<p>Database not reachable: " . htmlspecialchars(mysqli_connect_error()) . "</p>";
    }
} else {
    echo "<p>mysqli extension is not available in this php-cgi build.</p>";
}
?>
