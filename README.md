# ExcelTranslate

ExcelTranslate is a service to run a calculation service based on Excel files via ActiveMQ.

## Message Format
All messages are of `TextMessage` type. Each messages are composed of tokens separated by `'\u0006'` (in this document it is represented by `'|'` for readability).
Each message is optionally terminated by `'\u0006'`.

The format is designed for size and simple marshalling/unmarshalling.
### Input messages
Every input starts with a command token the supported tokens are 
* `iarr`
   This requests the definition of the input array and must be followed by the formula token:
   
   `iarr|my formula identifier|`
   
   The response is the [Input description](#input-description)
* `oarr`
  This requests the definition of the input array and must be followed by the formula token:
     
     `oarr|my formula identifier|`
     
     The response is the [Output description](#output-description)    
* `calc`  
    This requests the definition of the input array and must be followed by the formula token and

    `calc|my formula identifier|3|123.2|Text|0`
    
    All values are concatenated in the following grouping :
    
    1. All values for the variable n starting from 1 
    
    2. All values of row r starting from 1
    3. All values of column c starting from one
    
    Suppose the `iarr|example` response is
    
    `OK|2|1|v1|0|2|3|2|v2|0|3|2`
    
    Then a valid calculation request is 
    `calc|example|12|v1r1c1|v1r1c2|v1r2c1|v1r2c2|v1r3c1|v1r3c2|
    v2r1c1|v2r1c2|v2r1c3|v2r2c1|v2r2c2|v2r2c3|`
    
    The response is the [Calculation result](#calculation-result)    

### Output messages

A common element in a response is the Variable:

`3|name|1|2|4`

This defines Variable number 3 with the name 'name' of type 1 as a matrix of 2 rows with 4 columns.
The type is one of

|Code|type|Semantics|Remark|
|---|---|---|---| 
|0|`string`|UTF-8 encoded text|must not contain the terminator character!|
|1|`number`|Numeral|Anything parseable as double in the en_US locale, empty value represents NaN|
|2|`bool`|Boolean|'0' or '1' |
|3|`date`|Date|treated as a numeric|

#### Error
The client application must check for error type response for any request. Errors are signalled by the prefix `KO` and followed by free text:

    KO|Some more or less descriptive error message
#### Input description
The Input description is a `OK` response followed by the number `n` of input variables and a sequence of `n` variables.
Example of two variables 'value1' and 'value2' both simple numeric values:

`OK|2|1|value1|1|1|1|2|value2|1|1|1`

The variables will be returned always in the order of their number, starting from 1.

### Output description
Same as Input description
### Calculation result
The Calculation result is a `OK` response followed by the number `n` of out variables and a sequence of `n` variables followed by their values.
    
    OK|1|1|result|1|1|1|1000.00
    
The values for matrix variables are a sequence of length rows*cols:
    
    1|texts|0|2|3|r1c1|r1c2|r1c3|r2c1|r2c2|r2c3    
    
## Running the server
Just start `service(.bat)` this use the reference.conf.
Either add your configuration as a path or as a java option `-Dconfig.file=./my.conf`

     
## Configuration 
See [reference.conf](src/main/resources/reference.conf) for defaults.
If the Excel files are located in the directory from where you run the service, almost no configuration is needed. 

### Listener configuration
At least one `listener` must be defined

```` 
listener {
  localBroker = ${localAMQ}
  WebSphereMQ = webSphereMQ
}
````

So the server will be able to connect to ActiveMQ and WebSphereMQ at once and share resources between them. The preferred way to define the listener object is via the name of the
configuration, this avoids initialization problems when substitutions are used.

#### REST Listener

```` 
REST {
  bind = "0.0.0.0"
  port = 8099
  apikeys = [abcdefabcdefabcdef,tzuiotzuiotzuio]
}
````

The `apikeys` arrays list the allowed `Authorization:token <apikey>` to access the REST interface. If `apikeys` is absent or empty, no authorization is required.
See the [HOCON](https://github.com/lightbend/config#optional-system-or-env-variable-overrides) documentation on how to use environment variables to simplify managed deployments
### Sheet configuration
As long as the file names match the formula id, no configuration is required besides the `excelDir`.
If a file cannot follow the naming convention, a `sheet` object needs to be added to the configuration:

```
sheets {
   formulaId : ${sheetDefaults} {
     file:"FormulaFile.xls"
    }
}
```

This definition is sufficient if the workbook contains a valid "FormulaIO" sheet or an URS macro for the binding definition.
It is possible to add the binding in the configuration:

```
sheets {
   formulaId : ${sheetDefaults} {
     file:"FormulaFile.xls"
     binding:{
         inputs:[
             {
                 name="Input1"
                 type=number
                 ref="Main!$B$5"
                 rows=1
                 cols=1
             }
             {
                 name="Input2"
                 type=number
                 ref="Main!$B$6"
                 rows=1
                 cols=1
             }
         ]
         outputs:[
             {
                 name="Re1"
                 type=number
                 ref="Main!$B$18"
                 rows=1
                 cols=1
             }
             {
                 name="Res2"
                 type=number
                 ref="Main!$B$19"
                 rows=1
                 cols=1
             }
             {
                 name="Res3"
                 type=number
                 ref="Main!$B$20"
                 rows=1
                 cols=1
             }
         ]
       }
    }
}
```

#### FormulaIO sheet
The binding definition can integrated into the workbook by simply adding a sheet "FormulaIO". See  [example.xlsx](https://raw.githubusercontent.com/csar/ExcelTranslate/master/example.xlsx) for a functioning template on how to use the binding for matrix and scalar inputs and outputs.
Input and Outputs are listed in their intended order.

|Column|Semantics|
|---|---| 
|Name|The name of the binding, this is relevant for the FormulaEngine|
|Input|`TRUE` for an input reference. `FALSE` for output values. The words "Input" and "Output" can be used instead of the boolean values
|Type|For now only `number` is supported
|Cell|The cell this variable refers to. For non-scalar variable, the top-left cell reference has to be entered.
|Range|For non-scalar variables the extend of the input or output is set by `rows * cols` 

### WatchInterval
By default changes to the files are ignored once they got loaded, though newly spawned instances will still load the current revision of the file. 
 
Adding

`watchInterval : 10s`

to the config will install a WatchService on `excelDir` that will invalidate running instances so that they can reload and make sure that only one revision is used in the computations.

### REST
Since 0.6 there is a simple REST interface. There are three GET methods exposed
#1 `/api/<Formala>/input` to retrieve the input definition
#2 `/api/<Formala>/output` to retrieve the output definition
#3 `/api/<Formala>/calculate` to send input and receive the results

Input and Output return the array of the Bind objects:
```
[
  {
    "cols": 1,
    "name": "vector",
    "ordinal": 1,
    "rows": 3,
    "type": "number"
  },
  {
    "cols": 1,
    "name": "sum",
    "ordinal": 2,
    "rows": 1,
    "type": "number"
  }
]
```
`type` can be `number`, `string`, `bool` or `date`. This is relevant on how the array of Value objects will be represented in the IO
of the `calculate` method:
```
[
  {
    "number": [
      -8.0,
      0.0,
      42.0
    ],
    "type": "number"
  },
  {
    "number": [
      38.0
    ],
    "type": "number"
  }
]
```
As `type` is optional, only one  of `string`, `bool` or `date` should be specified (There will be more eleborated rules in the future).
Not that calls to the `calculate` method must include the header value `Content-Type:application/json`.

Analog to the queue interface above, the order in the arrays for Values in `calculate` correspond to the Bind arrays in `input` and
`output` respictively.


### Logging
For production it is recommended to set the Akka log level

`akka.loglevel=WARN` 

The general logging is by default defined by [logback.xml](src/main/resources/logback.xml) and can be overriden by the java option `-Dlogback.configurationFile=/path/to/config.xml`

## sbt tasks

To make a zip that contains the packaged application use `sbt universal:packageBin`

To create a Docker stage use `sbt docker:stage`

See <https://www.scala-sbt.org/sbt-native-packager/index.html> for more tasks and additional configuration.
