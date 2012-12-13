var commandPath = "$PATH";
if (!/\/$/.test(commandPath))
{
    commandPath += "/";
}
commandPath += "repl-cmd.txt";

var Log = (function () {
    // According to Appium,
    //16384 is the buffer size used by instruments
    var forceFlush = [],
        N = 16384, i = N;
    while(i--) { forceFlush[i] = "*"; }
    forceFlush = forceFlush.join('');

    return {
        result: function (status, data) {
            status = new String(status);
            status.type = "keyword";
            UIALogger.logMessage(clj.stringify({"status":status, "value":data},
                                               { keys_are_keywords: true }));
            UIALogger.logMessage(forceFlush);
        },
        output: function (msg) {
            UIALogger.logMessage(clj.stringify({"output":msg}));
            UIALogger.logMessage(forceFlush);
        }
    };
})();

var target = UIATarget.localTarget(),
    host = target.host();

target.setTimeout(0);

var expectedIndex = 0,//expected index of next command
    actualIndex,//actual index of next command by reading commandPath
    index,//index of ':' char in command
    exp,//expression to be eval'ed
    result,//result of eval
    input,//command
    process;//host command process

while (true)
{
    try
    {
        process = host.performTaskWithPathArgumentsTimeout("/bin/cat",
                                                           [commandPath],
                                                           2);

    } catch (e)
    {
        Log.output("Timeout on cat...");
        target.delay(0.1);
        continue;
    }
    if (process.exitCode != 0)
    {
        Log.output("unable to execute /bin/cat " + commandPath + " exitCode " + process.exitCode + ". Error: " + process.stderr);
    }
    else
    {
        input = process.stdout;
        try
        {
            index = input.indexOf(":", 0);
            if (index > -1) {
                actualIndex = parseInt(input.substring(0,index),10);
                if (!isNaN(actualIndex) && actualIndex >= expectedIndex) {
                    exp = input.substring(index+1, input.length);
                    result = eval(exp);
                }
                else {//likely old command is lingering...
                    continue;
                }
            }
            else {
                continue;
            }

        }
        catch (err)
        {
            Log.result("error", err.toString() + "  " + (err.stack ? err.stack.toString() : ""));
            expectedIndex++;
            continue;
        }

        expectedIndex++;
        Log.result("success",result);

    }
}
