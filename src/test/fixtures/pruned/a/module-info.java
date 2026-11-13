module com.example.pa {
    exports com.example.gone;
    exports com.example.pa to com.example.stub;
    requires com.example.stub;
    uses com.example.pa.NoProv;
    provides com.example.pa.Spi with com.example.pa.Impl;
}